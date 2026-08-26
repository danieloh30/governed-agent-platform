const repositoryUrl = "https://github.com/danieloh30/governed-agent-platform";

function updateRepositoryLinks() {
  document.querySelectorAll(`a[href="${repositoryUrl}"]`).forEach((link) => {
    link.target = "_blank";
    link.rel = "noopener noreferrer";
  });
}

if (typeof document$ !== "undefined") {
  document$.subscribe(updateRepositoryLinks);
} else {
  document.addEventListener("DOMContentLoaded", updateRepositoryLinks);
}
