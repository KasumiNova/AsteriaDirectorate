/// <reference types="vite/client" />

import type React from 'react';

type MdElementProps = React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>;

declare module 'react/jsx-runtime' {
  namespace JSX {
    interface IntrinsicElements {
      'md-elevated-card': MdElementProps;
      'md-filled-button': MdElementProps;
      'md-outlined-button': MdElementProps;
      'md-outlined-select': MdElementProps & {
        label?: string;
        name?: string;
        value?: string;
      };
      'md-select-option': MdElementProps & {
        value?: string;
      };
      'md-slider': MdElementProps & {
        labeled?: boolean;
        max?: number;
        min?: number;
        step?: number;
        ticks?: boolean;
        value?: number;
      };
      'md-switch': MdElementProps & {
        selected?: boolean;
      };
      'md-tabs': MdElementProps & {
        'active-tab-index'?: string;
      };
      'md-primary-tab': MdElementProps;
      'md-outlined-text-field': MdElementProps & {
        label?: string;
        name?: string;
        readOnly?: boolean;
        rows?: string;
        type?: string;
        value?: string;
      };
    }
  }
}
