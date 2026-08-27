mermaid.initialize({
  startOnLoad: false,
  securityLevel: "loose",
});

document$.subscribe(() => {
  mermaid.run({
    nodes: document.querySelectorAll(".mermaid:not([data-processed='true'])"),
  });
});
