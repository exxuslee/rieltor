(function () {
  const grid = document.querySelector('[data-featured-grid]');
  const featuredIds = [
    'apartment-central-park',
    'apartment-new-turnkey',
    'rc-olymp',
    'house-terrace-bucha'
  ];
  const featuredProperties = featuredIds
    .map(id => window.PROPERTIES.find(item => item.id === id))
    .filter(Boolean);
  if (grid) grid.innerHTML = featuredProperties.map(window.propertyCard).join('');

  document.querySelectorAll('[data-category-tab]').forEach(button => {
    button.addEventListener('click', () => {
      document.querySelectorAll('[data-category-tab]').forEach(tab => tab.classList.remove('is-active'));
      button.classList.add('is-active');
      const value = button.dataset.categoryTab;
      const items = value === 'all' ? featuredProperties : window.PROPERTIES.filter(item => item.category === value).slice(0, 4);
      grid.innerHTML = items.map(window.propertyCard).join('');
    });
  });

  const tiktokReviews = [
    { title: 'Відеоогляд нерухомості', label: 'Огляд 01', video: 'tiktok/IMG_5933.MOV' },
    { title: 'Відеоогляд нерухомості', label: 'Огляд 02', video: 'tiktok/IMG_5934.MP4' },
    { title: 'Відеоогляд нерухомості', label: 'Огляд 03', video: 'tiktok/IMG_5935.MP4' },
    { title: 'Відеоогляд нерухомості', label: 'Огляд 04', video: 'tiktok/IMG_5936.MP4' },
    { title: 'Відеоогляд нерухомості', label: 'Огляд 05', video: 'tiktok/IMG_5937.MP4' },
    { title: 'Відеоогляд нерухомості', label: 'Огляд 06', video: 'tiktok/IMG_5938.MP4' },
    { title: 'Відеоогляд нерухомості', label: 'Огляд 07', video: 'tiktok/IMG_5939.MP4' }
  ];
  const carousel = document.querySelector('[data-tiktok-carousel]');
  const previousButton = document.querySelector('[data-tiktok-prev]');
  const nextButton = document.querySelector('[data-tiktok-next]');
  const videoDialog = document.querySelector('[data-video-dialog]');
  const dialogPlayer = document.querySelector('[data-video-dialog-player]');
  const dialogTitle = document.querySelector('[data-video-dialog-title]');
  const dialogClose = document.querySelector('[data-video-dialog-close]');

  if (carousel && previousButton && nextButton) {
    carousel.innerHTML = tiktokReviews.map((review, index) => `
      <button class="tiktok-card" type="button" data-video-index="${index}" aria-label="${review.label}: відкрити відеоогляд">
        <video muted playsinline preload="metadata" aria-hidden="true" tabindex="-1"><source src="${review.video}"></video>
        <span class="tiktok-card__shade"></span>
        <span class="tiktok-card__number">0${index + 1}</span>
        <span class="tiktok-card__play" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M9 7.4v9.2L17 12 9 7.4Z"/></svg></span>
        <span class="tiktok-card__copy"><small>${review.label}</small><strong>${review.title}</strong><span>Дивитися відео →</span></span>
      </button>
    `).join('');

    carousel.querySelectorAll('.tiktok-card video').forEach(video => {
      video.addEventListener('loadedmetadata', () => {
        if (Number.isFinite(video.duration) && video.duration > 0.7) video.currentTime = 0.7;
      }, { once: true });
    });

    carousel.addEventListener('click', event => {
      const card = event.target.closest('[data-video-index]');
      if (!card || !videoDialog || !dialogPlayer) return;
      const review = tiktokReviews[Number(card.dataset.videoIndex)];
      dialogPlayer.src = review.video;
      dialogTitle.textContent = `${review.label} — ${review.title}`;
      videoDialog.showModal();
      dialogPlayer.play().catch(() => {});
    });

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

  const closeVideoDialog = () => {
    if (!videoDialog || !dialogPlayer) return;
    dialogPlayer.pause();
    dialogPlayer.removeAttribute('src');
    dialogPlayer.load();
    videoDialog.close();
  };
  dialogClose?.addEventListener('click', closeVideoDialog);
  videoDialog?.addEventListener('click', event => {
    if (event.target === videoDialog) closeVideoDialog();
  });
  videoDialog?.addEventListener('cancel', event => {
    event.preventDefault();
    closeVideoDialog();
  });
})();
