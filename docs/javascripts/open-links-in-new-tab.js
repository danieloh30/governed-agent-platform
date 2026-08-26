function openLinksInNewTab() {
  document.querySelectorAll("a[href]").forEach((link) => {
    const href = link.getAttribute("href");

    if (
      !href ||
      href.startsWith("#") ||
      href.startsWith("javascript:") ||
      href.startsWith("mailto:") ||
      href.startsWith("tel:")
    ) {
      return;
    }

    const destination = new URL(href, window.location.href);

    if (destination.origin !== window.location.origin) {
      link.target = "_blank";
      link.rel = "noopener noreferrer";
    } else {
      link.removeAttribute("target");
      link.removeAttribute("rel");
    }
  });
}

if (typeof document$ !== "undefined") {
  document$.subscribe(openLinksInNewTab);
} else {
  document.addEventListener("DOMContentLoaded", openLinksInNewTab);
}
