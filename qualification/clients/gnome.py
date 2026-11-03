#!/usr/bin/env python3
"""Optional installed GNOME Calendar qualification, reusing private DBus/X11/AT-SPI fixtures.

Only a disposable home and this process's own application accessibility tree are used.
No installed application, host trust store, existing calendar or user configuration changes.
"""
import argparse
import base64
import configparser
import ctypes
import json
import os
from pathlib import Path
import shutil
import secrets
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import unquote, urlsplit


def read_native_source(uid, root):
    """Use the installed public EDS C API; no additional GIR or test parser is needed."""
    pointer = ctypes.c_void_p
    glib = ctypes.CDLL('libglib-2.0.so.0')
    objects = ctypes.CDLL('libgobject-2.0.so.0')
    gio = ctypes.CDLL('libgio-2.0.so.0')
    sources = ctypes.CDLL('libedataserver-1.2.so.27')
    calendars = ctypes.CDLL('libecal-2.0.so.3')
    ical = ctypes.CDLL('libical-glib.so.3')
    def api(library, name, returns, *arguments):
        function = getattr(library, name)
        function.restype, function.argtypes = returns, list(arguments)
        return function
    unref = api(objects, 'g_object_unref', None, pointer)
    free = api(glib, 'g_free', None, pointer)
    free_error = api(glib, 'g_error_free', None, pointer)
    free_list = api(glib, 'g_slist_free_full', None, pointer, pointer)
    cancellable = api(gio, 'g_cancellable_new', pointer)()
    cancel = api(gio, 'g_cancellable_cancel', None, pointer)
    deadline = threading.Timer(45, lambda: cancel(cancellable))
    deadline.daemon = True
    deadline.start()
    retained, head = [], pointer()
    class Link(ctypes.Structure):
        pass
    Link._fields_ = [('data', pointer), ('next', ctypes.POINTER(Link))]
    def checked(function, label, *arguments):
        error = pointer()
        value = function(*arguments, ctypes.byref(error))
        if error:
            free_error(error)
            raise RuntimeError('Native EDS ' + label + ' failed')
        assert value, 'Native EDS ' + label + ' did not succeed'
        return value
    try:
        registry = checked(api(sources, 'e_source_registry_new_sync', pointer, pointer, ctypes.POINTER(pointer)),
                           'registry', cancellable)
        retained.append(registry)
        source = api(sources, 'e_source_registry_ref_source', pointer, pointer, ctypes.c_char_p)(registry, uid.encode())
        assert source, 'The wizard source must be present in the native registry'
        retained.append(source)
        client = checked(api(calendars, 'e_cal_client_connect_sync', pointer, pointer, ctypes.c_int,
                             ctypes.c_uint32, pointer, ctypes.POINTER(pointer)),
                         'connection', source, 0, 20, cancellable)  # E_CAL_CLIENT_SOURCE_TYPE_EVENTS
        retained.append(client)
        state = {'phase':'connecting', 'online':bool(api(sources,'e_client_is_online',ctypes.c_int,pointer)(client)),
                 'readonly':bool(api(sources,'e_client_is_readonly',ctypes.c_int,pointer)(client))}
        (root / 'backend-state.json').write_text(json.dumps(state,indent=2))
        checked(api(sources, 'e_client_refresh_sync', ctypes.c_int, pointer, pointer, ctypes.POINTER(pointer)),
                'refresh', client, cancellable)
        get_objects = api(calendars, 'e_cal_client_get_object_list_sync', ctypes.c_int, pointer, ctypes.c_char_p,
                          ctypes.POINTER(pointer), pointer, ctypes.POINTER(pointer))
        serialize = api(ical, 'i_cal_component_as_ical_string', pointer, pointer)
        get_uid = api(ical, 'i_cal_component_get_uid', ctypes.c_char_p, pointer)
        while True:
            checked(get_objects, 'object retrieval', client, b'#t', ctypes.byref(head), cancellable)
            components, uids = [], []
            link = ctypes.cast(head, ctypes.POINTER(Link))
            while link:
                assert len(components) < 20, 'Unexpected native fixture resource count'
                component = link.contents.data
                text = serialize(component)
                assert text
                try:
                    components.append(ctypes.string_at(text).decode('utf-8'))
                    uids.append(get_uid(component).decode('utf-8'))
                finally:
                    free(text)
                link = link.contents.next
            if set(uids) == {'portal-series', 'portal-second'}:
                state.update(phase='data-retrieved',cachedComponents=len(components),
                    readonly=bool(api(sources,'e_client_is_readonly',ctypes.c_int,pointer)(client)))
                (root / 'backend-state.json').write_text(json.dumps(state,indent=2))
                break
            if state.get('cachedComponents') != len(components):
                state['cachedComponents'] = len(components)
                (root / 'backend-state.json').write_text(json.dumps(state,indent=2))
            # Refresh initiates asynchronous backend work. Wait for actual cached data,
            # including any native credential prompt, under the existing 45-second cancellation.
            if head:
                free_list(head, ctypes.cast(unref, pointer))
                head.value = None
            time.sleep(.25)
        content = '\n'.join(components)
        assert content.count('BEGIN:VEVENT') == 3 and content.count('RECURRENCE-ID') == 1
        assert all(value in content for value in ('SUMMARY:Family time', 'SUMMARY:Moved occurrence',
                   'SUMMARY:Second event', 'X-PRIVATE:preserve', 'DURATION:P1D', 'COUNT=3', 'FREQ=DAILY'))
        assert all(value in content for value in ('DTSTART;TZID=Fixture/Fixed:20261102T090000',
                   'RECURRENCE-ID;TZID=Fixture/Fixed:20261103T090000', 'DTSTART;TZID=Fixture/Fixed:20261103T110000'))
        assert not api(sources, 'e_client_is_readonly', ctypes.c_int, pointer)(client), 'The owner calendar must remain writable in the native client'
        return {'uidSeries':2, 'eventComponents':3, 'recurrenceExceptions':1, 'writable':True,
                'interface':'installed EDS public ECalClient API after native wizard provisioning'}
    finally:
        deadline.cancel()
        deadline.join()
        if head:
            free_list(head, ctypes.cast(unref, pointer))
        for value in reversed(retained):
            unref(value)
        unref(cancellable)


def execute_child(root):
    import gi
    gi.require_version('Atspi', '2.0')
    gi.require_version('Gdk', '3.0')
    gi.require_version('GdkX11', '3.0')
    from gi.repository import Atspi, Gdk, GdkX11, Gio, GLib
    Gdk.init([])
    fixture = json.loads((root / 'client.json').read_text())
    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    address = bus.call_sync('org.a11y.Bus', '/org/a11y/bus', 'org.a11y.Bus', 'GetAddress', None,
                           GLib.VariantType.new('(s)'), Gio.DBusCallFlags.NONE, 10000, None).unpack()[0]
    os.environ['AT_SPI_BUS_ADDRESS'] = address
    # Unlock only this fixture's private login keyring through the supported daemon interface.
    subprocess.run(['gnome-keyring-daemon', '--unlock'], input=b'private-qualification-keyring\n',
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=15)
    subprocess.run(['gnome-keyring-daemon', '--start', '--components=secrets'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=15)
    login_keyring = bus.call_sync('org.freedesktop.secrets','/org/freedesktop/secrets',
        'org.freedesktop.Secret.Service','ReadAlias',GLib.Variant('(s)',('login',)),
        GLib.VariantType.new('(o)'),Gio.DBusCallFlags.NONE,10000,None).unpack()[0]
    assert login_keyring != '/', 'The private login keyring must be unlocked and available'
    bus.call_sync('org.freedesktop.secrets','/org/freedesktop/secrets','org.freedesktop.Secret.Service',
        'SetAlias',GLib.Variant('(so)',('default',login_keyring)),None,Gio.DBusCallFlags.NONE,10000,None)
    monitor = Gio.NetworkMonitor.get_default()
    (root / 'network-state.json').write_text(json.dumps({'monitor':monitor.__gtype__.name,
        'available':monitor.get_network_available()},indent=2))
    command = ['gnome-calendar']
    if os.environ.get('BUSYSTACK_GNOME_DEBUG') == '1':
        # Optional upstream-crash diagnosis only; suppress all function arguments and locals.
        command = ['gdb', '--batch', '--nx', '-ex', 'set startup-with-shell off',
                   '-ex', 'set print frame-arguments none', '-ex', 'set environment G_DEBUG fatal-criticals',
                   '-ex', 'run', '-ex', 'thread apply all backtrace', '--args', 'gnome-calendar']
    process = subprocess.Popen(command, stdout=(root / 'application.log').open('w'), stderr=subprocess.STDOUT)
    def capture(label):
        import ctypes
        x = ctypes.CDLL('libX11.so.6')
        pointer, window = ctypes.c_void_p, ctypes.c_ulong
        x.XOpenDisplay.restype = pointer
        x.XDefaultRootWindow.argtypes = [pointer]
        x.XDefaultRootWindow.restype = window
        x.XInternAtom.argtypes = [pointer, ctypes.c_char_p, ctypes.c_int]
        x.XInternAtom.restype = window
        x.XQueryTree.argtypes = [pointer, window, ctypes.POINTER(window), ctypes.POINTER(window), ctypes.POINTER(ctypes.POINTER(window)), ctypes.POINTER(ctypes.c_uint)]
        x.XGetWindowProperty.argtypes = [pointer, window, window, ctypes.c_long, ctypes.c_long, ctypes.c_int, window, ctypes.POINTER(window), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(window), ctypes.POINTER(window), ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte))]
        x.XFree.argtypes = [pointer]
        x.XCloseDisplay.argtypes = [pointer]
        x.XSetInputFocus.argtypes = [pointer, window, ctypes.c_int, window]
        x.XRaiseWindow.argtypes = [pointer, window]
        x.XFlush.argtypes = [pointer]
        display = x.XOpenDisplay(None)
        assert display
        pid_atom = x.XInternAtom(display, b'_NET_WM_PID', 0)
        owned_pids = {process.pid}
        # Under optional GDB diagnosis the accessible application is the debugger's child.
        # The private accessibility bus still identifies the exact native application PID.
        for accessible in nodes():
            if accessible.get_role_name() == 'application':
                owned_pids.add(accessible.get_process_id())
        matches = []
        def visit(parent, depth=0):
            if depth > 4: return
            rr, pp, count = window(), window(), ctypes.c_uint()
            children = ctypes.POINTER(window)()
            x.XQueryTree(display, parent, ctypes.byref(rr), ctypes.byref(pp), ctypes.byref(children), ctypes.byref(count))
            try:
                for index in range(count.value):
                    wid = children[index]
                    actual, size, remaining, fmt = window(), window(), window(), ctypes.c_int()
                    data = ctypes.POINTER(ctypes.c_ubyte)()
                    x.XGetWindowProperty(display, wid, pid_atom, 0, 1, 0, 0, ctypes.byref(actual), ctypes.byref(fmt), ctypes.byref(size), ctypes.byref(remaining), ctypes.byref(data))
                    if data:
                        if size.value and fmt.value == 32 and ctypes.cast(data, ctypes.POINTER(window))[0] in owned_pids:
                            matches.append(wid)
                        x.XFree(data)
                    visit(wid, depth + 1)
            finally:
                if children: x.XFree(children)
        try:
            visit(x.XDefaultRootWindow(display))
            for index, wid in enumerate(matches):
                native = GdkX11.X11Window.foreign_new_for_display(Gdk.Display.get_default(), wid)
                width, height = native.get_width(), native.get_height()
                if width >= 200 and height >= 100:
                    if label == 'initial':
                        x.XRaiseWindow(display, wid)
                        x.XSetInputFocus(display, wid, 2, 0)
                        x.XFlush(display)
                    pixbuf = Gdk.pixbuf_get_from_window(native, 0, 0, width, height)
                    if pixbuf: pixbuf.savev(str(root / (label + '-' + str(index) + '.png')), 'png', [], [])
        finally:
            x.XCloseDisplay(display)
    def pump():
        context = GLib.MainContext.default()
        while context.pending():
            context.iteration(False)
    owned_windows = []
    freeze_windows = False
    def nodes(showing=True):
        nonlocal owned_windows
        pump()
        desktop = Atspi.get_desktop(0)
        apps = [desktop.get_child_at_index(i) for i in range(desktop.get_child_count())]
        candidates = [a for a in apps if a.get_name().lower() in ('gnome-calendar', 'org.gnome.calendar')]
        if not candidates:
            return []
        application = candidates[0]
        # GTK 4.22's root GetChildAtIndex can return an unrealized/closed toplevel when
        # an AT-SPI child count is stale. Keep the known windows across modal closure;
        # ordinary widget contexts safely report defunct children through the same API.
        if not freeze_windows:
            application.clear_cache()
            owned_windows = [application.get_child_at_index(i)
                             for i in range(min(application.get_child_count(), 16))]
        result = [application]
        visited = 0
        def visit(item, depth=0):
            nonlocal visited
            if item is None or depth > 40 or visited >= 2000:
                return
            visited += 1
            try:
                if showing and not item.get_state_set().contains(Atspi.StateType.SHOWING):
                    return
                result.append(item)
                for n in range(min(item.get_child_count(), 160)):
                    visit(item.get_child_at_index(n), depth + 1)
            except GLib.Error:
                pass
        for owned in owned_windows:
            visit(owned)
        return result
    def snapshot(label):
        rows = [{'role': n.get_role_name(), 'name': '[password field]' if 'password' in n.get_role_name() else n.get_name(),
                 'states': [s.value_nick for s in n.get_state_set().get_states()],
                 'actions': [n.get_action_iface().get_action_name(i) for i in range(n.get_action_iface().get_n_actions())]
                    if n.get_role_name() == 'button' and n.get_action_iface() else []}
                for n in nodes()]
        (root / (label + '.json')).write_text(json.dumps(rows, indent=2).replace(fixture['credential'], '[redacted]'))
        if label in ('initial', 'discovered', 'connected', 'failed-network-address', 'failed-native-sync'):
            capture(label)
    def wait(condition, label, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('The native client exited during ' + label + ' with status ' + str(process.returncode))
            try:
                found = condition()
            except GLib.Error:
                # Dialog closure can invalidate accessibility objects during a tree traversal.
                found = None
            if found:
                return found
            time.sleep(.2)
        snapshot('failed-' + label)
        raise RuntimeError('GNOME timed out at ' + label)
    def named(name):
        return [n for n in nodes() if n.get_name() == name]
    def action(item):
        current = item
        for _ in range(5):
            iface = current.get_action_iface()
            if iface and iface.get_n_actions():
                names = [iface.get_action_name(i) for i in range(iface.get_n_actions())]
                selected = next((i for i, name in enumerate(names) if name in ('click', 'activate', 'widget.activate')),
                                0 if current.get_role_name() == 'button' else None)
                if selected is not None:
                    history_path = root / 'actions.json'
                    history = json.loads(history_path.read_text()) if history_path.exists() else []
                    history.append({'role':current.get_role_name(), 'name':current.get_name(), 'actions':names, 'selected':names[selected]})
                    history_path.write_text(json.dumps(history, indent=2))
                    assert iface.do_action(selected)
                    return
            current = current.get_parent()
            if current is None:
                break
        focused = False
        try:
            focused = item.get_role_name() == 'button' and item.get_component_iface().grab_focus()
        except GLib.Error:
            pass
        if focused:
            def owns_focus():
                item.clear_cache()
                return item.get_state_set().contains(Atspi.StateType.FOCUSED) and any(
                    n.get_role_name() == 'frame' and n.get_state_set().contains(Atspi.StateType.ACTIVE) for n in nodes())
            wait(owns_focus, 'owned-button-focus', 3)
            assert Atspi.generate_keyboard_event(Gdk.KEY_Return, None, Atspi.KeySynthType.SYM)
            history_path = root / 'actions.json'
            history = json.loads(history_path.read_text()) if history_path.exists() else []
            history.append({'role':'button', 'name':item.get_name(), 'selected':'Return with owned button/application focus verified'})
            history_path.write_text(json.dumps(history, indent=2))
            return
        if item.get_role_name() == 'button':
            rect = item.get_component_iface().get_extents(Atspi.CoordType.SCREEN)
            x, y = rect.x + rect.width // 2, rect.y + rect.height // 2
            frames = [n for n in nodes() if n.get_role_name() == 'frame'
                      and n.get_state_set().contains(Atspi.StateType.ACTIVE)]
            bounds = [n.get_component_iface().get_extents(Atspi.CoordType.SCREEN) for n in frames]
            if item.get_name() == 'Add Calendar' and rect.x == 0 and rect.y == 0:
                # This pinned AdwButtonRow reports a local rectangle as SCREEN coordinates.
                # Use the visually checked 50.0 centered dialog and its documented 15px bottom margin.
                dialogs = [n for n in nodes() if n.get_role_name() == 'dialog' and n.get_name() == 'Calendar Settings']
                assert len(dialogs) == 1 and len(bounds) == 1
                assert 700 <= bounds[0].width <= 1400 and 550 <= bounds[0].height <= 1000
                x = bounds[0].x + bounds[0].width // 2
                y = bounds[0].y + bounds[0].height // 2 + 500 // 2 - 15 - rect.height // 2
            assert rect.width > 0 and rect.height > 0 and any(b.x <= x < b.x+b.width and b.y <= y < b.y+b.height for b in bounds)
            import ctypes
            native_x = ctypes.CDLL('libX11.so.6')
            native_x.XOpenDisplay.restype = ctypes.c_void_p
            native_x.XFlush.argtypes = [ctypes.c_void_p]
            native_x.XCloseDisplay.argtypes = [ctypes.c_void_p]
            xtest = ctypes.CDLL('libXtst.so.6')
            xtest.XTestFakeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
            xtest.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
            native_display = native_x.XOpenDisplay(None)
            assert native_display
            try:
                assert xtest.XTestFakeMotionEvent(native_display, 0, x, y, 0)
                assert xtest.XTestFakeButtonEvent(native_display, 1, 1, 10)
                assert xtest.XTestFakeButtonEvent(native_display, 1, 0, 20)
                native_x.XFlush(native_display)
            finally:
                native_x.XCloseDisplay(native_display)
            history_path = root / 'actions.json'
            history = json.loads(history_path.read_text()) if history_path.exists() else []
            history.append({'role':'button', 'name':item.get_name(), 'selected':'XTEST click in authenticated private display within active owned application bounds', 'point':[x,y]})
            history_path.write_text(json.dumps(history, indent=2))
            return
        raise RuntimeError('The owned GNOME control has no accessible activation action')
    def click(name):
        def buttons():
            return [n for n in named(name) if n.get_role_name() == 'button'
                    and n.get_state_set().contains(Atspi.StateType.SENSITIVE)]
        matches = wait(buttons, 'control-' + name.replace(' ', '-'))
        action(matches[0])
    def editable():
        return [n for n in nodes() if n.get_state_set().contains(Atspi.StateType.EDITABLE)
                and n.get_editable_text_iface() is not None]
    try:
        wait(nodes, 'application')
        snapshot('initial')
        click('Manage Calendars')
        click('Add Calendar')
        wait(lambda: len(editable()) >= 2, 'network-address')
        connection_uri = fixture['connectionAddress']
        discovery = urlsplit(connection_uri)
        assert discovery.scheme == 'https' and discovery.password is None
        assert unquote(discovery.username or '') == fixture['username']
        assert discovery.hostname == urlsplit(fixture['address']).hostname
        assert discovery.port == urlsplit(fixture['address']).port
        editable()[-1].get_editable_text_iface().set_text_contents(connection_uri)
        wait(lambda: named('Credentials'), 'credential-dialog')
        snapshot('credential-prompt')
        fields = editable()
        password_fields = [n for n in fields if 'password' in n.get_role_name()]
        user_fields = [n for n in fields if n.get_name() == 'User']
        assert len(password_fields) == 1 and len(user_fields) == 1
        user_fields[0].get_editable_text_iface().set_text_contents(fixture['username'])
        password_fields[0].get_editable_text_iface().set_text_contents(fixture['credential'])
        freeze_windows = True
        click('Connect')
        wait(lambda: any(n.get_role_name() == 'button' and n.get_name() == 'Add Calendar'
                        and n.get_state_set().contains(Atspi.StateType.SENSITIVE) for n in nodes()), 'validated-source', 60)
        snapshot('discovered')
        click('Add Calendar')
        wait(lambda: any(fixture['name'] in n.get_name() for n in nodes()), 'saved-source')
        # The official source file is durable native-client state, independently of the server.
        def saved_sources():
            matched = []
            for path in (root / 'config/evolution/sources').glob('*.source'):
                config = configparser.ConfigParser(interpolation=None)
                config.read(path)
                if config.get('Data Source', 'DisplayName', fallback='') == fixture['name']:
                    matched.append((path, config))
            return matched
        matched = wait(saved_sources, 'saved-source-file')
        assert len(matched) == 1, 'Exactly the selected collection must be provisioned'
        source, config = matched[0]
        (root / 'source-metadata.json').write_text(json.dumps({
            'parent':config.get('Data Source','Parent',fallback=''),
            'user':config.get('Authentication','User',fallback=''),
            'method':config.get('Authentication','Method',fallback=''),
            'host':config.get('Authentication','Host',fallback=''),
            'path':config.get('WebDAV Backend','ResourcePath',fallback='')},indent=2))
        assert config.get('Calendar', 'BackendName') == 'caldav'
        assert config.get('Security', 'Method') == 'tls'
        assert config.get('Authentication', 'User') == fixture['username']
        assert config.get('Authentication', 'Host') == urlsplit(fixture['address']).hostname
        assert config.getint('Authentication', 'Port') == (urlsplit(fixture['address']).port or 443)
        assert config.get('WebDAV Backend', 'ResourcePath') == urlsplit(fixture['address']).path
        colour = config.get('Calendar', 'Color', fallback='')
        assert colour.upper() == '#126A5EFF', 'Native discovery must preserve the portal colour'
        # Finish the real wizard, then authenticate the backend if it prompts separately.
        def in_settings(item):
            for _ in range(20):
                if item is None: return False
                if item.get_role_name() == 'dialog' and item.get_name() == 'Calendar Settings': return True
                item = item.get_parent()
            return False
        close = [n for n in named('Close') if n.get_role_name() == 'button' and in_settings(n)]
        assert len(close) == 1
        action(close[0])
        freeze_windows = False
        native = {}
        def retrieve():
            try:
                native['result'] = read_native_source(source.stem, root)
            except Exception as failure:
                native['error'] = str(failure)
        worker = threading.Thread(target=retrieve, daemon=True)
        worker.start()
        def synced():
            nonlocal freeze_windows
            if 'error' in native: raise RuntimeError(native['error'])
            if 'result' in native: return native['result']
            desktop = Atspi.get_desktop(0)
            apps = [desktop.get_child_at_index(i) for i in range(min(desktop.get_child_count(),20))]
            (root / 'backend-applications.json').write_text(json.dumps([
                {'name':app.get_name(),'pid':app.get_process_id()} for app in apps if app],indent=2))
            passwords = [n for n in editable() if 'password' in n.get_role_name()
                         or n.get_name().strip().rstrip(':').casefold() == 'password']
            if passwords:
                assert len(passwords) == 1
                snapshot('backend-credential-prompt')
                passwords[0].get_editable_text_iface().set_text_contents(fixture['credential'])
                buttons = [n for n in nodes() if n.get_role_name() == 'button' and n.get_name() in ('Connect','Authenticate','OK')
                           and n.get_state_set().contains(Atspi.StateType.SENSITIVE)]
                assert len(buttons) == 1
                freeze_windows = True
                action(buttons[0])
            return None
        try:
            synchronization = wait(synced, 'native-event-synchronization', 60)
        except Exception:
            snapshot('failed-native-sync')
            raise
        report = {'status': 'passed', 'client': 'GNOME Calendar', 'version': fixture['gnomeVersion'],
                  'dataServerVersion': fixture['edsVersion'], 'mode': 'real native connection wizard in private home/DBus/X11 namespace',
                  'tls': 'normal verification with fixture CA only in private namespace',
                  'steps': ['Manage Calendars', 'Add Calendar', 'trusted HTTPS discovery URL with username', 'Credentials User/Password', 'Connect', 'native discovery', 'Add Calendar', 'native backend password prompt', 'native event synchronization'],
                  'nativeBackend': 'caldav', 'nativeColour': colour, 'synchronization':synchronization}
        (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        snapshot('connected')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--child', type=Path)
    args = parser.parse_args()
    if args.child:
        execute_child(args.child)
        return
    if not shutil.which('bwrap') or not shutil.which('xauth'):
        raise SystemExit('GNOME qualification requires bubblewrap, xauth and installed GNOME/AT-SPI prerequisites')
    assert args.fixture and args.output
    fixture = json.loads(args.fixture.read_text())
    versions = {package: subprocess.check_output(['dpkg-query', '-W', '-f=${Version}', package], text=True).strip()
                for package in ('gnome-calendar', 'evolution-data-server')}
    assert versions == {'gnome-calendar': '50.0-1', 'evolution-data-server': '3.56.2-8'}, 'The installed native qualification versions differ'
    fixture.update(gnomeVersion=versions['gnome-calendar'], edsVersion=versions['evolution-data-server'])
    with tempfile.TemporaryDirectory(prefix='busystack-portal-gnome-') as directory:
        root = Path(directory)
        for name in ('home', 'run', 'config', 'data', 'cache', 'state', 'X11'):
            (root / name).mkdir(mode=0o700)
        (root / 'X11').chmod(0o1777)
        shutil.copyfile(__file__, root / 'gnome.py')
        (root / 'client.json').write_text(json.dumps(fixture))
        (root / 'client.json').chmod(0o600)
        shutil.copyfile(fixture['ca'], root / 'ca.pem')
        (root / 'trust.pem').write_bytes(Path('/etc/ssl/certs/ca-certificates.crt').read_bytes() + (root / 'ca.pem').read_bytes())
        (root / 'bus.conf').write_text('<busconfig><apparmor mode="disabled"/><type>session</type><listen>unix:tmpdir=' + str(root / 'run') + '</listen><standard_session_servicedirs/><policy context="default"><allow send_destination="*"/><allow eavesdrop="true"/><allow own="*"/></policy></busconfig>')
        # Supported private-bus configuration: read-only /sys cannot service AppArmor policy queries.
        accessibility_config = Path('/usr/share/defaults/at-spi2/accessibility.conf')
        (root / 'a11y-bus.conf').write_text(accessibility_config.read_text().replace('<busconfig>', '<busconfig><apparmor mode="disabled"/>', 1))
        env = os.environ.copy()
        for key in ('DBUS_SESSION_BUS_ADDRESS', 'DBUS_STARTER_ADDRESS', 'DBUS_STARTER_BUS_TYPE', 'AT_SPI_BUS_ADDRESS', 'WAYLAND_DISPLAY', 'SSH_AUTH_SOCK'):
            env.pop(key, None)
        env.update(XDG_RUNTIME_DIR=str(root / 'run'), XDG_CONFIG_HOME=str(root / 'config'), XDG_DATA_HOME=str(root / 'data'),
                   XDG_CACHE_HOME=str(root / 'cache'), XDG_STATE_HOME=str(root / 'state'), GDK_BACKEND='x11', GSK_RENDERER='cairo',
                   GTK_A11Y='atspi', NO_AT_BRIDGE='0', TZ='America/Vancouver', LANG='C.UTF-8', LC_ALL='C.UTF-8',
                   GNOME_KEYRING_CONTROL=str(root / 'run/keyring'), XDG_SESSION_TYPE='x11')
        display = ':' + str(100 + secrets.randbelow(9000))
        (root / 'xauth').touch(mode=0o600)
        subprocess.run(['xauth', '-f', str(root / 'xauth'), 'source', '-'],
                       input='add ' + display + ' . ' + secrets.token_hex(16) + '\n', text=True, check=True, capture_output=True)
        env.update(DISPLAY=display, XAUTHORITY=str(root / 'xauth'))
        display_name = 'busystack-gnome-display-' + secrets.token_hex(6)
        display_log = (root / 'xvfb.log').open('w')
        display_process = subprocess.Popen(['docker', 'run', '--rm', '--name', display_name, '--network', 'none',
            '--user', str(os.getuid()) + ':' + str(os.getgid()),
            '--volume', str(root / 'X11') + ':/tmp/.X11-unix:rw', '--volume', str(root / 'xauth') + ':/fixture/xauth:ro',
            '--entrypoint', '/usr/bin/Xvfb', fixture['displayImage'], display, '-screen', '0', '1366x900x24',
            '-nolisten', 'tcp', '-auth', '/fixture/xauth', '-noreset'], stdout=display_log, stderr=subprocess.STDOUT)
        command = ['bwrap', '--ro-bind', '/', '/', '--tmpfs', '/tmp', '--bind', str(root), str(root),
                   '--ro-bind', str(root / 'X11'), '/tmp/.X11-unix', '--bind', str(root / 'home'), str(Path.home()),
                   '--chdir', str(root),
                   '--ro-bind', str(root / 'trust.pem'), '/etc/ssl/certs/ca-certificates.crt', '--proc', '/proc', '--dev', '/dev',
                   '--ro-bind', str(root / 'a11y-bus.conf'), str(accessibility_config),
                   '--unshare-pid', '--die-with-parent', 'dbus-run-session', '--config-file', str(root / 'bus.conf'), '--',
                   '/usr/bin/python3', str(root / 'gnome.py'), '--child', str(root)]
        try:
            deadline = time.monotonic() + 30
            while not (root / 'X11' / ('X' + display[1:])).exists():
                if display_process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('The private authenticated Xvfb display did not start')
                time.sleep(.1)
            try:
                result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=240)
            except subprocess.TimeoutExpired as failure:
                def decoded(value):
                    return value.decode(errors='replace') if isinstance(value, bytes) else (value or '')
                result = subprocess.CompletedProcess(command, 124, decoded(failure.stdout), decoded(failure.stderr))
        finally:
            subprocess.run(['docker', 'rm', '--force', display_name], capture_output=True, timeout=15)
            try:
                display_process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                display_process.terminate()
                try:
                    display_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    display_process.kill()
                    display_process.wait(timeout=5)
            display_log.close()
            args.output.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / 'xvfb.log', args.output / 'gnome-display.log')
        args.output.mkdir(parents=True, exist_ok=True)
        # Retain only redacted fixture diagnostics and accessibility names, never credentials/source files/keyrings.
        diagnostic = result.stdout + result.stderr
        for secret in (fixture['credential'],):
            diagnostic = diagnostic.replace(secret, '[redacted]')
        (args.output / 'gnome-process.log').write_text(diagnostic)
        application_log = (root / 'application.log').read_text(errors='replace') if (root / 'application.log').exists() else ''
        for secret in (fixture['credential'], base64.b64encode((fixture['username'] + ':' + fixture['credential']).encode()).decode()):
            application_log = application_log.replace(secret, '[redacted]')
        (args.output / 'gnome-application.log').write_text(application_log)
        for path in root.glob('*.json'):
            if path.name != 'client.json':
                shutil.copyfile(path, args.output / ('gnome-' + path.name))
        for path in root.glob('*.png'):
            shutil.copyfile(path, args.output / ('gnome-' + path.name))
        if result.returncode != 0:
            raise RuntimeError('Native GNOME connection wizard qualification failed; see redacted fixture diagnostics')
        print('GNOME_NATIVE_CONNECTION_WIZARD_PASSED', flush=True)


if __name__ == '__main__':
    main()
