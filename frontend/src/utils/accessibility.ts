// Accessibility utilities for WCAG AA compliance

/**
 * Checks if a color meets WCAG AA contrast ratio requirements
 * @param foreground - The foreground color in hex format
 * @param background - The background color in hex format
 * @returns Object with contrast ratio and compliance status
 */
export function checkContrastRatio(foreground: string, background: string) {
  const getLuminance = (color: string): number => {
    const rgb = hexToRgb(color);
    if (!rgb) return 0;
    
    const { r, g, b } = rgb;
    
    const [rs, gs, bs] = [r, g, b].map(c => {
      c = c / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    });
    
    return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs;
  };
  
  const l1 = getLuminance(foreground);
  const l2 = getLuminance(background);
  
  const contrast = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  
  return {
    ratio: Math.round(contrast * 100) / 100,
    passAA: contrast >= 4.5,
    passAALarge: contrast >= 3,
    passAAA: contrast >= 7,
  };
}

/**
 * Converts hex color to RGB
 */
function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  return result
    ? {
        r: parseInt(result[1], 16),
        g: parseInt(result[2], 16),
        b: parseInt(result[3], 16),
      }
    : null;
}

/**
 * Generates a unique ID for form elements
 */
export function generateId(prefix: string = 'id'): string {
  return `${prefix}-${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Checks if an element is visible to screen readers
 */
export function isVisibleToScreenReaders(element: HTMLElement): boolean {
  const style = window.getComputedStyle(element);
  
  return !(
    style.display === 'none' ||
    style.visibility === 'hidden' ||
    style.opacity === '0' ||
    element.hasAttribute('aria-hidden') ||
    element.hidden
  );
}

/**
 * Announces text to screen readers using aria-live
 */
export function announceToScreenReader(
  message: string,
  priority: 'polite' | 'assertive' = 'polite'
): void {
  const announcer = document.createElement('div');
  announcer.setAttribute('aria-live', priority);
  announcer.setAttribute('aria-atomic', 'true');
  announcer.className = 'sr-only';
  announcer.textContent = message;
  
  document.body.appendChild(announcer);
  
  // Remove after announcement
  setTimeout(() => {
    document.body.removeChild(announcer);
  }, 1000);
}

/**
 * Creates proper ARIA labels for complex UI elements
 */
export function createAriaLabel(
  baseLabel: string,
  state?: string,
  description?: string
): string {
  let label = baseLabel;
  
  if (state) {
    label += `, ${state}`;
  }
  
  if (description) {
    label += `. ${description}`;
  }
  
  return label;
}

/**
 * Validates form accessibility
 */
export function validateFormAccessibility(form: HTMLFormElement): {
  valid: boolean;
  issues: string[];
} {
  const issues: string[] = [];
  const inputs = form.querySelectorAll('input, textarea, select');
  
  inputs.forEach((input) => {
    const element = input as HTMLInputElement;
    
    // Check for labels
    const label = form.querySelector(`label[for="${element.id}"]`);
    const ariaLabel = element.getAttribute('aria-label');
    const ariaLabelledBy = element.getAttribute('aria-labelledby');
    
    if (!label && !ariaLabel && !ariaLabelledBy) {
      issues.push(`Input ${element.name || element.type} lacks proper labeling`);
    }
    
    // Check for required field indicators
    if (element.required && !element.getAttribute('aria-required')) {
      issues.push(`Required field ${element.name} should have aria-required="true"`);
    }
    
    // Check for error states
    if (element.getAttribute('aria-invalid') === 'true') {
      const errorId = element.getAttribute('aria-describedby');
      if (!errorId || !form.querySelector(`#${errorId}`)) {
        issues.push(`Invalid field ${element.name} lacks proper error description`);
      }
    }
  });
  
  return {
    valid: issues.length === 0,
    issues,
  };
}

/**
 * Sets up keyboard navigation for custom components
 */
export function setupKeyboardNavigation(
  container: HTMLElement,
  focusableSelector: string = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
): void {
  const focusableElements = container.querySelectorAll(focusableSelector) as NodeListOf<HTMLElement>;
  
  const handleKeyDown = (e: KeyboardEvent) => {
    const currentIndex = Array.from(focusableElements).indexOf(document.activeElement as HTMLElement);
    
    switch (e.key) {
      case 'ArrowDown':
      case 'ArrowRight':
        e.preventDefault();
        const nextIndex = (currentIndex + 1) % focusableElements.length;
        focusableElements[nextIndex].focus();
        break;
        
      case 'ArrowUp':
      case 'ArrowLeft':
        e.preventDefault();
        const prevIndex = (currentIndex - 1 + focusableElements.length) % focusableElements.length;
        focusableElements[prevIndex].focus();
        break;
        
      case 'Home':
        e.preventDefault();
        focusableElements[0].focus();
        break;
        
      case 'End':
        e.preventDefault();
        focusableElements[focusableElements.length - 1].focus();
        break;
    }
  };
  
  container.addEventListener('keydown', handleKeyDown);
}

/**
 * Medical-specific accessibility constants
 */
export const MEDICAL_A11Y = {
  // Minimum touch target size for medical interfaces (larger than standard 44px)
  MIN_TOUCH_TARGET: 48,
  
  // Timeouts for medical contexts (longer than typical web)
  SESSION_WARNING_TIME: 5 * 60 * 1000, // 5 minutes
  SESSION_TIMEOUT: 30 * 60 * 1000, // 30 minutes
  
  // ARIA roles for medical contexts
  ROLES: {
    MEDICAL_FORM: 'form',
    PATIENT_INFO: 'region',
    SOAP_NOTE: 'document',
    RECORDING_STATUS: 'status',
    ERROR_ALERT: 'alert',
  },
  
  // Color contrast ratios for medical interfaces
  CONTRAST: {
    NORMAL_TEXT: 4.5,
    LARGE_TEXT: 3,
    NON_TEXT: 3,
  },
} as const;