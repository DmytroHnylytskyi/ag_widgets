'use strict';

const { contextBridge, ipcRenderer } = require('electron');

/**
 * The only surface the renderer gets. Every channel is re-validated in
 * `src/main/ipc.js` against the app's own webContents (widget or settings).
 */
contextBridge.exposeInMainWorld('agWidgets', {
  getState: () => ipcRenderer.invoke('agw:get-state'),
  minimizeWindow: () => ipcRenderer.invoke('agw:minimize'),
  refresh: () => ipcRenderer.invoke('agw:refresh'),
  zcodeLogin: () => ipcRenderer.invoke('agw:zcode-login'),
  zcodeCancelLogin: () => ipcRenderer.invoke('agw:zcode-cancel'),
  zcodeLogout: () => ipcRenderer.invoke('agw:zcode-logout'),
  openExternalAction: (action) => ipcRenderer.invoke('agw:open-action', action),
  quitApp: () => ipcRenderer.invoke('agw:quit'),
  showMenu: () => ipcRenderer.invoke('agw:menu'),
  moveWindow: (dx, dy) => ipcRenderer.invoke('agw:move-window', dx, dy),
  saveWindowPosition: () => ipcRenderer.invoke('agw:move-end'),
  /** Open (or focus) the separate Settings window. */
  openSettings: () => ipcRenderer.invoke('agw:open-settings'),
  /** Full settings snapshot: appearance, effective theme, widget/window flags. */
  getSettings: () => ipcRenderer.invoke('agw:get-settings'),
  /** Patch appearance and/or window flags; resolves to the fresh snapshot. */
  updateSettings: (patch) => ipcRenderer.invoke('agw:update-settings', patch),
  /** Reset appearance.json to defaults; resolves to the fresh snapshot. */
  resetAppearance: () => ipcRenderer.invoke('agw:reset-appearance'),
  onStateUpdate: (cb) => {
    const handler = (_event, payload) => cb(payload);
    ipcRenderer.on('agw:state', handler);
    return () => ipcRenderer.removeListener('agw:state', handler);
  },
  /** Subscribe to live appearance changes; returns a cleanup function. */
  onAppearanceUpdate: (cb) => {
    const handler = (_event, payload) => cb(payload);
    ipcRenderer.on('agw:appearance', handler);
    return () => ipcRenderer.removeListener('agw:appearance', handler);
  },
});
