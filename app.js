// Scroll progress bar (simple UX micro-interaction)
(function () {
  const bar = document.querySelector("[data-progress]");
  if (!bar) return;

  function update() {
    const doc = document.documentElement;
    const scrollTop = doc.scrollTop || document.body.scrollTop;
    const scrollHeight = doc.scrollHeight - doc.clientHeight;
    const progress = scrollHeight > 0 ? (scrollTop / scrollHeight) * 100 : 0;
    bar.style.width = `${Math.min(100, Math.max(0, progress))}%`;
  }

  window.addEventListener("scroll", update, { passive: true });
  window.addEventListener("resize", update);
  update();
})();

// SA Designs project cover: use the supplied hero screenshot directly.
document.querySelectorAll('img[src="./assets/images/sa-designs.svg"]').forEach((img) => {
  img.src = "./assets/images/sa-designs-hero.jpg";
});
