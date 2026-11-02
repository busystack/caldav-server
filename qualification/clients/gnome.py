#!/usr/bin/env python3
"""Optional installed GNOME Calendar qualification, reusing private DBus/X11/AT-SPI fixtures.

Only a disposable home and this process's own application accessibility tree are used.
No installed application, host trust store, existing calendar or user configuration changes.
"""
import argparse
import configparser
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit


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
    process = subprocess.Popen(['gnome-calendar'], stdout=(root / 'application.log').open('w'), stderr=subprocess.STDOUT)
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
        display = x.XOpenDisplay(None)
        assert display
        pid_atom = x.XInternAtom(display, b'_NET_WM_PID', 0)
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
                        if size.value and fmt.value == 32 and ctypes.cast(data, ctypes.POINTER(window))[0] == process.pid:
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
                    pixbuf = Gdk.pixbuf_get_from_window(native, 0, 0, width, height)
                    if pixbuf: pixbuf.savev(str(root / (label + '-' + str(index) + '.png')), 'png', [], [])
        finally:
            x.XCloseDisplay(display)
    def pump():
        context = GLib.MainContext.default()
        while context.pending():
            context.iteration(False)
    def nodes():
        pump()
        desktop = Atspi.get_desktop(0)
        apps = [desktop.get_child_at_index(i) for i in range(desktop.get_child_count())]
        candidates = [a for a in apps if a.get_name().lower() in ('gnome-calendar', 'org.gnome.calendar')]
        if not candidates:
            return []
        result = []
        def visit(item, depth=0):
            if item is None or depth > 40 or len(result) >= 2000:
                return
            try:
                item.clear_cache()
                if item.get_state_set().contains(Atspi.StateType.SHOWING):
                    result.append(item)
                for n in range(min(item.get_child_count(), 160)):
                    visit(item.get_child_at_index(n), depth + 1)
            except GLib.Error:
                pass
        visit(candidates[0])
        return result
    def snapshot(label):
        rows = [{'role': n.get_role_name(), 'name': '[password field]' if 'password' in n.get_role_name() else n.get_name(),
                 'states': [s.value_nick for s in n.get_state_set().get_states()],
                 'actions': [n.get_action_iface().get_action_name(i) for i in range(n.get_action_iface().get_n_actions())]
                    if n.get_role_name() == 'button' and n.get_action_iface() else []}
                for n in nodes()]
        (root / (label + '.json')).write_text(json.dumps(rows, indent=2))
        if label in ('initial', 'discovered', 'connected', 'failed-network-address'):
            capture(label)
    def wait(condition, label, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            found = condition()
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
            assert rect.width > 0 and rect.height > 0 and any(b.x <= x < b.x+b.width and b.y <= y < b.y+b.height for b in bounds)
            assert Atspi.generate_mouse_event(x, y, 'b1c')
            history_path = root / 'actions.json'
            history = json.loads(history_path.read_text()) if history_path.exists() else []
            history.append({'role':'button', 'name':item.get_name(), 'selected':'native click within active owned application bounds'})
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
        editable()[-1].get_editable_text_iface().set_text_contents(fixture['address'])
        wait(lambda: named('Credentials'), 'credential-dialog')
        fields = editable()
        password_fields = [n for n in fields if 'password' in n.get_role_name()]
        user_fields = [n for n in fields if 'password' not in n.get_role_name()]
        assert password_fields and user_fields
        user_fields[-1].get_editable_text_iface().set_text_contents(fixture['username'])
        password_fields[-1].get_editable_text_iface().set_text_contents(fixture['credential'])
        click('Connect')
        wait(lambda: named(fixture['name']), 'discovered-collection', 60)
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
        assert config.get('Calendar', 'BackendName') == 'caldav'
        assert config.get('Security', 'Method') == 'tls'
        assert config.get('Authentication', 'User') == fixture['username']
        assert config.get('Authentication', 'Host') == urlsplit(fixture['address']).hostname
        assert config.get('WebDAV Backend', 'ResourcePath') == urlsplit(fixture['address']).path
        colour = config.get('Calendar', 'Color', fallback='')
        report = {'status': 'passed', 'client': 'GNOME Calendar', 'version': fixture['gnomeVersion'],
                  'dataServerVersion': fixture['edsVersion'], 'mode': 'real native connection wizard in private home/DBus/X11 namespace',
                  'tls': 'normal verification with fixture CA only in private namespace',
                  'steps': ['Manage Calendars', 'Add Calendar', 'collection HTTPS URL', 'Credentials User/Password', 'Connect', 'native discovery', 'Add Calendar'],
                  'nativeBackend': 'caldav', 'nativeColour': colour}
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
    if not os.environ.get('DISPLAY') or not shutil.which('bwrap'):
        raise SystemExit('GNOME qualification requires the existing Linux X11 display, bubblewrap and native GNOME/AT-SPI prerequisites')
    assert args.fixture and args.output
    fixture = json.loads(args.fixture.read_text())
    versions = {package: subprocess.check_output(['dpkg-query', '-W', '-f=${Version}', package], text=True).strip()
                for package in ('gnome-calendar', 'evolution-data-server')}
    assert versions == {'gnome-calendar': '50.0-1', 'evolution-data-server': '3.56.2-8'}, 'The installed native qualification versions differ'
    fixture.update(gnomeVersion=versions['gnome-calendar'], edsVersion=versions['evolution-data-server'])
    with tempfile.TemporaryDirectory(prefix='busystack-portal-gnome-') as directory:
        root = Path(directory)
        for name in ('home', 'run', 'config', 'data', 'cache', 'state'):
            (root / name).mkdir(mode=0o700)
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
                   XDG_CACHE_HOME=str(root / 'cache'), XDG_STATE_HOME=str(root / 'state'), GDK_BACKEND='x11',
                   GTK_A11Y='atspi', GTK_USE_PORTAL='0', NO_AT_BRIDGE='0', TZ='America/Vancouver', LANG='C.UTF-8', LC_ALL='C.UTF-8',
                   GNOME_KEYRING_CONTROL=str(root / 'run/keyring'))
        if env.get('XAUTHORITY'):
            shutil.copyfile(env['XAUTHORITY'], root / 'xauth')
            (root / 'xauth').chmod(0o600)
            env['XAUTHORITY'] = str(root / 'xauth')
        command = ['bwrap', '--ro-bind', '/', '/', '--tmpfs', '/tmp', '--bind', str(root), str(root),
                   '--ro-bind', '/tmp/.X11-unix', '/tmp/.X11-unix', '--bind', str(root / 'home'), str(Path.home()),
                   '--chdir', str(root),
                   '--ro-bind', str(root / 'trust.pem'), '/etc/ssl/certs/ca-certificates.crt', '--proc', '/proc', '--dev', '/dev',
                   '--ro-bind', str(root / 'a11y-bus.conf'), str(accessibility_config),
                   '--unshare-pid', '--die-with-parent', 'dbus-run-session', '--config-file', str(root / 'bus.conf'), '--',
                   '/usr/bin/python3', str(root / 'gnome.py'), '--child', str(root)]
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=240)
        args.output.mkdir(parents=True, exist_ok=True)
        # Retain only redacted fixture diagnostics and accessibility names, never credentials/source files/keyrings.
        diagnostic = result.stdout + result.stderr
        for secret in (fixture['credential'],):
            diagnostic = diagnostic.replace(secret, '[redacted]')
        (args.output / 'gnome-process.log').write_text(diagnostic)
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
