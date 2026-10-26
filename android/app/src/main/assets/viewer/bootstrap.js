// Runs before ES-module resolution so failed offline dependencies cannot leave
// the product stuck on an infinite loading indicator.
function reportLoadFailure(error) {
  const loading = document.getElementById('loading');
  if (loading) {
    loading.style.display = 'grid';
    loading.textContent = '空间暂时无法显示，请返回后重试。';
  }
  try { window.RoomScan?.reportState(JSON.stringify({ ready: false, error: String(error) })); } catch {}
}
window.addEventListener('error', event => reportLoadFailure(event.message || 'Offline asset could not load'));
window.addEventListener('unhandledrejection', event => reportLoadFailure(event.reason));
