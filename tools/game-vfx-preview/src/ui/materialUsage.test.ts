import { MATERIAL_COMPONENT_IMPORTS } from './materialComponents';

describe('Material Design 3 component usage', () => {
  it('declares MD3 web components for all interactive controls', () => {
    expect(MATERIAL_COMPONENT_IMPORTS).toEqual(expect.arrayContaining([
      '@material/web/button/filled-button.js',
      '@material/web/button/outlined-button.js',
      '@material/web/labs/card/elevated-card.js',
      '@material/web/select/outlined-select.js',
      '@material/web/select/select-option.js',
      '@material/web/slider/slider.js',
      '@material/web/switch/switch.js',
      '@material/web/tabs/primary-tab.js',
      '@material/web/tabs/tabs.js',
      '@material/web/textfield/outlined-text-field.js',
    ]));
  });
});
