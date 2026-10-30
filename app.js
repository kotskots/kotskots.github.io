// Scroll progress bar
(function () {
  const bar = document.querySelector("[data-progress]");
  if (!bar) return;

  function update() {
    const doc = document.documentElement;
    const scrollHeight = doc.scrollHeight - doc.clientHeight;
    const progress = scrollHeight > 0 ? (doc.scrollTop / scrollHeight) * 100 : 0;
    bar.style.width = `${Math.min(100, Math.max(0, progress))}%`;
  }

  window.addEventListener("scroll", update, { passive: true });
  window.addEventListener("resize", update);
  update();
})();

// Fade sections in as they scroll into view
(function () {
  const items = document.querySelectorAll(".reveal");
  if (!("IntersectionObserver" in window)) {
    items.forEach((el) => el.classList.add("is-visible"));
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-visible");
      observer.unobserve(entry.target);
    });
  }, { rootMargin: "0px 0px -8% 0px" });
  items.forEach((el) => observer.observe(el));
  window.addEventListener("beforeprint", () => items.forEach((el) => el.classList.add("is-visible")));
})();

// Filter projects by year
(function () {
  const buttons = document.querySelectorAll("[data-filter]");
  const works = document.querySelectorAll(".work[data-year]");
  buttons.forEach((button) => {
    button.addEventListener("click", () => {
      const filter = button.dataset.filter;
      buttons.forEach((b) => b.setAttribute("aria-pressed", String(b === button)));
      works.forEach((work) => {
        work.hidden = filter !== "all" && work.dataset.year !== filter;
        work.classList.add("is-visible");
      });
    });
  });
})();
