// Override these values in this file when your browser reaches services on
// different hosts or ports. Defaults work for a local developer machine.
window.ETB_CONFIG = {
  // The included server proxies these paths to the backend services so the
  // browser stays same-origin (no CORS configuration is required).
  catalogUrl: '/api/catalog',
  bookingUrl: '/api/booking',
  inventoryUrl: '/api/inventory'
};
