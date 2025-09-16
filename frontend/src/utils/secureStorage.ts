// Secure storage utilities for medical data protection and HIPAA compliance

export interface SecureStorageOptions {
  encrypt?: boolean;
  ttl?: number; // Time to live in milliseconds
  namespace?: string;
}

interface StorageItem {
  value: any;
  timestamp: number;
  ttl?: number;
  encrypted?: boolean;
}

class SecureStorage {
  private readonly namespace: string;
  private readonly defaultTTL: number = 30 * 60 * 1000; // 30 minutes

  constructor(namespace: string = 'curasnap') {
    this.namespace = namespace;
  }

  /**
   * Store data securely in sessionStorage with automatic expiration
   */
  setItem(key: string, value: any, options: SecureStorageOptions = {}): void {
    try {
      const item: StorageItem = {
        value,
        timestamp: Date.now(),
        ttl: options.ttl || this.defaultTTL,
        encrypted: options.encrypt || false,
      };

      const storageKey = this.getStorageKey(key);

      // For medical data, consider encryption (simplified version)
      if (options.encrypt) {
        item.value = this.simpleEncrypt(JSON.stringify(value));
      }

      sessionStorage.setItem(storageKey, JSON.stringify(item));

      // Set cleanup timer
      if (item.ttl) {
        setTimeout(() => {
          this.removeItem(key);
        }, item.ttl);
      }
    } catch (error) {
      console.warn('Failed to store data securely:', error);
    }
  }

  /**
   * Retrieve data from secure storage with expiration check
   */
  getItem<T>(key: string): T | null {
    try {
      const storageKey = this.getStorageKey(key);
      const storedData = sessionStorage.getItem(storageKey);

      if (!storedData) {
        return null;
      }

      const item: StorageItem = JSON.parse(storedData);

      // Check if item has expired
      if (item.ttl && Date.now() - item.timestamp > item.ttl) {
        this.removeItem(key);
        return null;
      }

      // Decrypt if necessary
      if (item.encrypted) {
        const decrypted = this.simpleDecrypt(item.value);
        return JSON.parse(decrypted) as T;
      }

      return item.value as T;
    } catch (error) {
      console.warn('Failed to retrieve secure data:', error);
      this.removeItem(key); // Remove corrupted data
      return null;
    }
  }

  /**
   * Remove specific item from storage
   */
  removeItem(key: string): void {
    try {
      const storageKey = this.getStorageKey(key);
      sessionStorage.removeItem(storageKey);
    } catch (error) {
      console.warn('Failed to remove secure data:', error);
    }
  }

  /**
   * Clear all data for this namespace
   */
  clear(): void {
    try {
      const keysToRemove: string[] = [];

      // Find all keys with our namespace
      for (let i = 0; i < sessionStorage.length; i++) {
        const key = sessionStorage.key(i);
        if (key?.startsWith(`${this.namespace}:`)) {
          keysToRemove.push(key);
        }
      }

      // Remove all namespace keys
      keysToRemove.forEach(key => {
        sessionStorage.removeItem(key);
      });
    } catch (error) {
      console.warn('Failed to clear secure storage:', error);
    }
  }

  /**
   * Clear all browser storage (emergency wipe for data protection)
   */
  emergencyWipe(): void {
    try {
      sessionStorage.clear();
      localStorage.clear();

      // Clear any cached data
      if ('caches' in window) {
        caches.keys().then(cacheNames => {
          cacheNames.forEach(cacheName => {
            caches.delete(cacheName);
          });
        }).catch(console.warn);
      }

      // Clear IndexedDB if available
      if ('indexedDB' in window) {
        indexedDB.databases?.().then(databases => {
          databases.forEach(db => {
            if (db.name) {
              indexedDB.deleteDatabase(db.name);
            }
          });
        }).catch(console.warn);
      }
    } catch (error) {
      console.warn('Failed to perform emergency wipe:', error);
    }
  }

  private getStorageKey(key: string): string {
    return `${this.namespace}:${key}`;
  }

  // Simple XOR encryption for sensitive data (in production, use proper encryption)
  private simpleEncrypt(data: string): string {
    const key = 'curasnap-medical-2024'; // In production, use a proper key derivation
    let encrypted = '';

    for (let i = 0; i < data.length; i++) {
      encrypted += String.fromCharCode(
        data.charCodeAt(i) ^ key.charCodeAt(i % key.length)
      );
    }

    return btoa(encrypted); // Base64 encode
  }

  private simpleDecrypt(encryptedData: string): string {
    try {
      const encrypted = atob(encryptedData); // Base64 decode
      const key = 'curasnap-medical-2024';
      let decrypted = '';

      for (let i = 0; i < encrypted.length; i++) {
        decrypted += String.fromCharCode(
          encrypted.charCodeAt(i) ^ key.charCodeAt(i % key.length)
        );
      }

      return decrypted;
    } catch (error) {
      throw new Error('Failed to decrypt data');
    }
  }
}

// Singleton instance for medical data
export const medicalStorage = new SecureStorage('medical');

// General secure storage
export const secureStorage = new SecureStorage('curasnap');

// Auto-cleanup on page unload (additional protection)
window.addEventListener('beforeunload', () => {
  // Quick cleanup of sensitive data
  medicalStorage.clear();
});

// Auto-cleanup on visibility change (when user switches tabs)
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    // User switched away, start cleanup timer
    setTimeout(() => {
      if (document.hidden) {
        medicalStorage.clear();
      }
    }, 5 * 60 * 1000); // 5 minutes of inactivity
  }
});

// Emergency cleanup on window close
window.addEventListener('pagehide', () => {
  medicalStorage.emergencyWipe();
});