import { createContext, useContext } from 'react';
export const UiLocaleContext = createContext('en');
export const useUiLocale = () => useContext(UiLocaleContext);
