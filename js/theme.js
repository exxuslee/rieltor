(function () {
    const root = document.documentElement;
    const saved = localStorage.getItem('site-theme');
    const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    root.dataset.theme = saved || (systemDark ? 'dark' : 'light');

    window.toggleTheme = function () {
        const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
        root.dataset.theme = next;
        localStorage.setItem('site-theme', next);
        document.dispatchEvent(new CustomEvent('themechange', {detail: next}));
    };
})();
