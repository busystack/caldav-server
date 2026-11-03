package org.busystack.caldav.management;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.busystack.caldav.calendar.CalendarInterchange;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PortabilityController {
  private final PortalService portal;
  private final PortabilityService portability;

  public PortabilityController(PortalService portal, PortabilityService portability) {
    this.portal = portal;
    this.portability = portability;
  }

  @GetMapping("/portability")
  String page(
      Authentication auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String destination,
      Model model) {
    model.addAttribute("collectionPage", portal.collections(auth, "all", "", page));
    model.addAttribute("selectedDestination", destination);
    return "portability";
  }

  @PostMapping("/portability/preview")
  String preview(
      Authentication auth,
      @RequestParam UUID destination,
      @RequestParam MultipartFile file,
      Model model)
      throws IOException {
    if (file.isEmpty() || file.getSize() > CalendarInterchange.MAX_FILE_BYTES)
      throw new org.busystack.caldav.storage.StoreException(
          org.busystack.caldav.storage.StoreException.Kind.LIMIT);
    byte[] content;
    try (var input = file.getInputStream()) {
      content = input.readNBytes(CalendarInterchange.MAX_FILE_BYTES + 1);
    }
    if (content.length > CalendarInterchange.MAX_FILE_BYTES)
      throw new org.busystack.caldav.storage.StoreException(
          org.busystack.caldav.storage.StoreException.Kind.LIMIT);
    model.addAttribute("report", portability.preview(auth, destination, content));
    return "import-report";
  }

  @PostMapping("/portability/import/{token}")
  String confirm(
      Authentication auth,
      @PathVariable UUID token,
      @RequestParam(defaultValue = "false") boolean confirmed,
      Model model)
      throws IOException {
    model.addAttribute("report", portability.confirm(auth, token, confirmed));
    return "import-report";
  }

  @PostMapping("/portability/import/{token}/cancel")
  String cancel(Authentication auth, @PathVariable UUID token, RedirectAttributes flash) {
    portability.cancel(auth, token);
    flash.addFlashAttribute("notice", "Import preview discarded. No data was imported.");
    return "redirect:/portability";
  }

  @GetMapping("/portability/collections/{id}/export")
  ModelAndView export(
      Authentication auth,
      @PathVariable UUID id,
      HttpServletResponse response,
      RedirectAttributes flash)
      throws IOException {
    var export = portability.export(auth, id);
    try {
      response.setHeader("Cache-Control", "no-store");
      if (export.length() == 0) {
        flash.addFlashAttribute(
            "notice",
            "This export snapshot contained no calendar or task items. No file was downloaded.");
        return new ModelAndView("redirect:/collections/" + id);
      }
      response.setContentType("text/calendar; charset=utf-8");
      response.setHeader(
          "Content-Disposition",
          "attachment; filename=\"busystack-collection"
              + (export.stream() ? "-stream" : "")
              + ".ics\"");
      response.setContentLengthLong(export.length());
      Files.copy(export.path(), response.getOutputStream());
      return null;
    } finally {
      Files.deleteIfExists(export.path());
    }
  }
}
