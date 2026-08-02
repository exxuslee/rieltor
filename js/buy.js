(function () {
  const form = document.querySelector('.selection-form');
  if (!form) return;

  const category = new URLSearchParams(location.search).get('category');
  const availableCategories = Array.from(form.elements.category?.options || [], option => option.value);
  if (category && availableCategories.includes(category)) {
    form.elements.category.value = category;
  }
})();
