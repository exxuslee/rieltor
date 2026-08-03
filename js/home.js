(function () {
  const grid = document.querySelector('[data-featured-grid]');
  if (grid) grid.innerHTML = window.PROPERTIES.slice(0, 3).map(window.propertyCard).join('');

  document.querySelectorAll('[data-category-tab]').forEach(button => {
    button.addEventListener('click', () => {
      document.querySelectorAll('[data-category-tab]').forEach(tab => tab.classList.remove('is-active'));
      button.classList.add('is-active');
      const value = button.dataset.categoryTab;
      const items = value === 'all' ? window.PROPERTIES.slice(0, 3) : window.PROPERTIES.filter(item => item.category === value).slice(0, 3);
      grid.innerHTML = items.map(window.propertyCard).join('');
    });
  });

  const tiktokProfile = 'https://www.tiktok.com/@irina_rieltor_novator';
  const tiktokReviews = [
    { title: 'Огляди квартир в Ірпені', label: 'Квартири', image: 'images/properties/apartment-park.png' },
    { title: 'Що варто знати про новобудови', label: 'Новобудови', image: 'images/properties/new-building.png' },
    { title: 'Будинки для життя за містом', label: 'Будинки', image: 'images/properties/house-bucha.png' },
    { title: 'Квартира з готовим ремонтом', label: 'Румтур', image: 'images/properties/apartment-new.png' },
    { title: 'Ділянки та тихі локації', label: 'Земля', image: 'images/properties/land-bucha.png' },
    { title: 'Приміщення для вашого бізнесу', label: 'Комерція', image: 'images/properties/commercial.png' }
  ];
  const carousel = document.querySelector('[data-tiktok-carousel]');
  const previousButton = document.querySelector('[data-tiktok-prev]');
  const nextButton = document.querySelector('[data-tiktok-next]');

  if (carousel && previousButton && nextButton) {
    carousel.innerHTML = tiktokReviews.map((review, index) => `
      <a class="tiktok-card" href="${tiktokProfile}" target="_blank" rel="noopener noreferrer" aria-label="${review.title}: дивитися в TikTok">
        <img src="${review.image}" alt="" loading="lazy">
        <span class="tiktok-card__shade"></span>
        <span class="tiktok-card__number">0${index + 1}</span>
        <span class="tiktok-card__play" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M9 7.4v9.2L17 12 9 7.4Z"/></svg></span>
        <span class="tiktok-card__copy"><small>${review.label}</small><strong>${review.title}</strong><span>Дивитися в TikTok ↗</span></span>
      </a>
    `).join('');

    const updateCarouselControls = () => {
      const maxScroll = carousel.scrollWidth - carousel.clientWidth;
      previousButton.disabled = carousel.scrollLeft < 8;
      nextButton.disabled = carousel.scrollLeft > maxScroll - 8;
    };
    const moveCarousel = direction => {
      const card = carousel.querySelector('.tiktok-card');
      const gap = parseFloat(getComputedStyle(carousel).gap) || 18;
      carousel.scrollBy({ left: direction * ((card?.offsetWidth || 280) + gap), behavior: 'smooth' });
    };

    previousButton.addEventListener('click', () => moveCarousel(-1));
    nextButton.addEventListener('click', () => moveCarousel(1));
    carousel.addEventListener('scroll', updateCarouselControls, { passive: true });
    window.addEventListener('resize', updateCarouselControls);
    updateCarouselControls();
    if (window.location.hash === '#tiktok-reviews-title') {
      requestAnimationFrame(() => document.getElementById('tiktok-reviews-title')?.scrollIntoView());
    }
  }
})();
