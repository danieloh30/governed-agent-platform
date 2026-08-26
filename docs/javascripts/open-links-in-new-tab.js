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

    link.target = "_blank";
    link.rel = "noopener noreferrer";
  });
}

if (typeof document$ !== "undefined") {
  document$.subscribe(openLinksInNewTab);
} else {
  document.addEventListener("DOMContentLoaded", openLinksInNewTab);
}
