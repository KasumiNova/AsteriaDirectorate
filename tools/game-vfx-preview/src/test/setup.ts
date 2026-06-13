import '@testing-library/jest-dom/vitest';

HTMLElement.prototype.attachInternals = function attachInternals() {
  return {
    ariaLabel: '',
    checkValidity: () => true,
    form: null,
    labels: [],
    reportValidity: () => true,
    setFormValue: () => undefined,
    setValidity: () => undefined,
    validationMessage: '',
    validity: {},
    willValidate: false,
  } as unknown as ElementInternals;
};

if (!Element.prototype.scrollTo) {
  Element.prototype.scrollTo = () => undefined;
}
