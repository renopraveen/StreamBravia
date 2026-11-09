/**
 * StreamBravia TV Application Logic
 * Pure vanilla JavaScript — Zero DOM framework overhead, zero latency.
 * Pure In-App Fullscreen TV Player with live BitTorrent sequential streaming,
 * automatic playback on click, and 10-foot spatial D-Pad remote control.
 */

let currentType = 'movie';
let currentGenre = '';
let currentRegionalLang = 'all';
let currentYear = '';
let currentItems = [];
let selectedItem = null;
let currentStreams = [];
let isFetchingStreams = false;
let autoPlayRequested = false;
let activeResolvedStream = null;
let streamHudInterval = null;
let logsInterval = null;

// TV Player & Seek State
let currentTotalDuration = 7200; // Default 2 hours if metadata missing
let inactivityTimer = null;
let seekPressCount = 0;
let lastSeekPressTime = 0;
let pendingSeekTarget = 0;
let seekCommitTimeout = null;
let isSeeking = false;
let isBuffering = false;      // True while video is stalled waiting for data
let hasAutoStarted = false;   // True once initial playback autoplay triggers
let baseStreamUrl = '';       // Base stream URL without ?start= or ?audio= params
let currentStreamOffset = 0;  // Offset in seconds when user seeks into stream
let activeSubtitle = null;
let availableSubtitles = [];

// DOM Elements
const mediaGrid = document.getElementById('media-grid');
const heroBanner = document.getElementById('hero-banner');
const heroTitle = document.getElementById('hero-title');
const heroBg = document.getElementById('hero-bg');
const heroRating = document.getElementById('hero-rating');
const heroYear = document.getElementById('hero-year');
const heroGenre = document.getElementById('hero-genre');
const heroDesc = document.getElementById('hero-desc');
const heroPlayBtn = document.getElementById('hero-play-btn');
const heroDetailsBtn = document.getElementById('hero-details-btn');

// Modals
const detailsModal = document.getElementById('details-modal');
const modalCloseBtn = document.getElementById('modal-close-btn');
const modalPoster = document.getElementById('modal-poster');
const modalTitle = document.getElementById('modal-title');
const modalRating = document.getElementById('modal-rating');
const modalYear = document.getElementById('modal-year');
const modalRuntime = document.getElementById('modal-runtime');
const modalGenres = document.getElementById('modal-genres');
const modalSynopsis = document.getElementById('modal-synopsis');
const modalCast = document.getElementById('modal-cast');
const streamsList = document.getElementById('streams-list');

// Source Selection Elements (Torrentio vs TamilBlasters)
const tabSourceTorrentio = document.getElementById('tab-source-torrentio');
const tabSourceTamilblasters = document.getElementById('tab-source-tamilblasters');
const sectionTorrentioStreams = document.getElementById('section-torrentio-streams');
const sectionTamilblastersStreams = document.getElementById('section-tamilblasters-streams');
const badgeCountTorrentio = document.getElementById('badge-count-torrentio');
const badgeCountTamilblasters = document.getElementById('badge-count-tamilblasters');
const tamilblastersStreamsList = document.getElementById('tamilblasters-streams-list');
const tamilblastersErrorBox = document.getElementById('tamilblasters-error-box');

let currentTamilBlastersStreams = [];
let isFetchingTamilBlasters = false;
let activeSourceTab = 'torrentio';
let activeTbQualityFilter = 'all';

// Player Modal & Elements
const playerModal = document.getElementById('player-modal');
const videoPlayer = document.getElementById('video-player');
const playerTitle = document.getElementById('player-title');
const playerSubtitle = document.getElementById('player-subtitle');
const playerLoadingSpinner = document.getElementById('player-loading-spinner');
const tvLoadingText = document.getElementById('tv-loading-text');
const tvLoadingSub = document.getElementById('tv-loading-sub');

// TV Seek Bar & Controls Elements
const tvControlsOverlay = document.getElementById('tv-controls-overlay');
const tvSeekBarWrapper = document.getElementById('tv-seek-bar-wrapper');
const tvSeekBuffered = document.getElementById('tv-seek-buffered');
const tvSeekProgress = document.getElementById('tv-seek-progress');
const tvSeekThumb = document.getElementById('tv-seek-thumb');
const seekCurrentTime = document.getElementById('seek-current-time');
const seekTotalTime = document.getElementById('seek-total-time');

// Accelerating Seek Pill
const seekPill = document.getElementById('seek-pill');
const seekPillIcon = document.getElementById('seek-pill-icon');
const seekPillDelta = document.getElementById('seek-pill-delta');
const seekPillTime = document.getElementById('seek-pill-time');

// TV Control Buttons (Icon-Only Mode)
const btnPlayPause = document.getElementById('btn-play-pause');
const svgPlay = document.getElementById('svg-play');
const svgPause = document.getElementById('svg-pause');
const btnRewind = document.getElementById('btn-rewind');
const btnForward = document.getElementById('btn-forward');
const playerSubBtn = document.getElementById('player-sub-btn');
const playerAudioBtn = document.getElementById('player-audio-btn');
const btnHudToggle = document.getElementById('btn-hud-toggle');

// Audio Modal Elements
const audioModal = document.getElementById('audio-modal');
const audioCloseBtn = document.getElementById('audio-close-btn');
const audioTrackList = document.getElementById('audio-track-list');
const audioModalActiveBadge = document.getElementById('audio-modal-active-badge');

// Subtitle Modal Elements
const subtitleModal = document.getElementById('subtitle-modal');
const subCloseBtn = document.getElementById('sub-close-btn');
const subLangSelect = document.getElementById('sub-lang-select');
const btnSearchSubs = document.getElementById('btn-search-subs');
const subActiveList = document.getElementById('sub-active-list');
const subSearchResults = document.getElementById('sub-search-results');

// Streaming HUD items
const playerStreamHud = document.getElementById('player-stream-hud');
const hudSpeed = document.getElementById('hud-speed');
const hudPeers = document.getElementById('hud-peers');
const hudBuffer = document.getElementById('hud-buffer');
const hudFile = document.getElementById('hud-file');

// Settings Modal
const settingsModal = document.getElementById('settings-modal');
const settingsBtn = document.getElementById('settings-btn');
const settingsCloseBtn = document.getElementById('settings-close-btn');
const saveSettingsBtn = document.getElementById('save-settings-btn');
const rdTokenInput = document.getElementById('rd-token-input');
const settingsStatus = document.getElementById('settings-status');

// OTA Update Modal Elements
const updateModal = document.getElementById('update-modal');
const updateCloseBtn = document.getElementById('update-close-btn');
const updateOldVersion = document.getElementById('update-old-version');
const updateNewVersion = document.getElementById('update-new-version');
const updateSizeBadge = document.getElementById('update-size-badge');
const updateReleaseTitle = document.getElementById('update-release-title');
const updateChangelog = document.getElementById('update-changelog');
const updateProgressContainer = document.getElementById('update-progress-container');
const updateProgressBar = document.getElementById('update-progress-bar');
const updateProgressText = document.getElementById('update-progress-text');
const updateProgressPct = document.getElementById('update-progress-pct');
const btnUpdateInstall = document.getElementById('btn-update-install');
const btnUpdateLater = document.getElementById('btn-update-later');
const btnSettingsCheckUpdate = document.getElementById('btn-settings-check-update');
const settingsUpdateStatus = document.getElementById('settings-update-status');
const settingsAppVersion = document.getElementById('settings-app-version');
const settingsRepoName = document.getElementById('settings-repo-name');

let currentActiveUpdate = null;
let updatePollingTimer = null;

// HLS.js MSE Player Engine instance
let hlsInstance = null;

function loadMediaStream(streamUrl, startPosSec = 0) {
  if (hlsInstance) {
    try {
      hlsInstance.destroy();
    } catch (e) {}
    hlsInstance = null;
  }

  const isHls = streamUrl.includes('.m3u8');
  if (isHls && window.Hls && Hls.isSupported()) {
    logClient('INFO', `Initializing Hls.js MSE Engine for: ${streamUrl}`);
    hlsInstance = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: 60,
      maxBufferLength: 20,
      maxMaxBufferLength: 40,
      maxBufferSize: 30 * 1000 * 1000,
      startPosition: startPosSec > 0 ? startPosSec : 0,
      startFragPrefetch: true,
      progressive: true,
      maxBufferHole: 0.3,
      manifestLoadingMaxRetry: 15,
      manifestLoadingRetryDelay: 400,
      levelLoadingMaxRetry: 8,
      fragLoadingMaxRetry: 8
    });

    hlsInstance.loadSource(streamUrl);
    hlsInstance.attachMedia(videoPlayer);

    hlsInstance.on(Hls.Events.MANIFEST_PARSED, () => {
      logClient('INFO', 'Hls.js manifest parsed successfully');
      if (startPosSec > 0) {
        videoPlayer.currentTime = startPosSec;
      }
      videoPlayer.play().catch(e => {
        logClient('WARN', `Autoplay prevented by browser: ${e.message}`);
      });
    });

    hlsInstance.on(Hls.Events.ERROR, (event, data) => {
      if (data.fatal) {
        logClient('WARN', `Hls.js error: ${data.type} - ${data.details}`);
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            hlsInstance.startLoad();
            break;
          case Hls.ErrorTypes.MEDIA_ERROR:
            hlsInstance.recoverMediaError();
            break;
          default:
            hlsInstance.destroy();
            hlsInstance = null;
            break;
        }
      }
    });
  } else {
    // Native HTML5 playback
    videoPlayer.src = streamUrl;
    if (startPosSec > 0) {
      const onSeekResume = () => {
        videoPlayer.currentTime = startPosSec;
        updateSeekBar();
        videoPlayer.removeEventListener('loadedmetadata', onSeekResume);
      };
      videoPlayer.addEventListener('loadedmetadata', onSeekResume);
    }
    videoPlayer.play().catch(e => {
      logClient('WARN', `Native video play waiting for data: ${e.message}`);
    });
  }
}

// Logs Modal
const logsModal = document.getElementById('logs-modal');
const btnLogs = document.getElementById('btn-logs');
const logsCloseBtn = document.getElementById('logs-close-btn');
const logsTerminal = document.getElementById('logs-terminal');
const logsRefreshBtn = document.getElementById('logs-refresh-btn');
const logsClearBtn = document.getElementById('logs-clear-btn');

// Genre Menu & Sub-Filter Bar Elements
const genreMenu = document.getElementById('genre-menu');
const btnGenres = document.getElementById('btn-genres');
const regionalFilterRow = document.getElementById('regional-filter-row');
const yearFilterRow = document.getElementById('year-filter-row');
const yearPillsContainer = document.getElementById('year-pills-container');

// Search & Voice Search
const searchInput = document.getElementById('search-input');
const searchBtn = document.getElementById('search-btn');
const voiceSearchBtn = document.getElementById('voice-search-btn');
const voiceOverlay = document.getElementById('voice-overlay');
const voiceTitle = document.getElementById('voice-title');
const voiceSubtitle = document.getElementById('voice-subtitle');
const voicePulseRing = document.getElementById('voice-pulse-ring');
const voiceWave = document.getElementById('voice-wave');

// Toast
const toast = document.getElementById('toast');

// Netflix Play & Collapsible
const modalPlayNowBtn = document.getElementById('modal-play-now-btn');
const modalPlaySub = document.getElementById('modal-play-sub') || { textContent: '' };
const modalPlayMain = document.querySelector('.netflix-play-main');
const toggleStreamsBtn = document.getElementById('toggle-streams-btn');
const streamsCollapsibleContent = document.getElementById('streams-collapsible-content');
const accordionArrow = document.getElementById('accordion-arrow');
const accordionTitle = document.getElementById('accordion-title');

// TV Series Selector Elements & State
const seriesSelector = document.getElementById('series-selector');
const seriesSeasonsRow = document.getElementById('series-seasons-row');
const seriesEpisodesRow = document.getElementById('series-episodes-row');
const btnNextEp = document.getElementById('btn-next-ep');
const btnPrevEp = document.getElementById('btn-prev-ep');
let currentSeriesVideos = [];
let currentSelectedSeason = 1;
let currentSelectedEpisode = 1;
let currentPlayingEpisode = null;

// Next Episode Pre-fetch Cache
let preloadedNextStream = null;

// Watch History
const HISTORY_KEY = 'streambravia_history';
const HISTORY_MAX_ITEMS = 200;

function getHistory() {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '{}'); } catch (e) { return {}; }
}

function saveHistory(h) {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(h)); } catch (e) {}
}

function updateWatchHistory(item, season, episode, position, duration) {
  if (!item || !item.id) return;
  const h = getHistory();
  const key = item.id;
  const existing = h[key] || {};
  h[key] = {
    ...existing,
    id: item.id,
    name: item.name,
    type: item.type,
    poster: item.poster,
    season: season || null,
    episode: episode || null,
    position: Math.floor(position),
    duration: Math.floor(duration) || 7200,
    timestamp: Date.now(),
    doNotRecommend: existing.doNotRecommend || false
  };
  // Trim to max
  const keys = Object.keys(h).sort((a, b) => h[b].timestamp - h[a].timestamp);
  if (keys.length > HISTORY_MAX_ITEMS) {
    keys.slice(HISTORY_MAX_ITEMS).forEach(k => delete h[k]);
  }
  saveHistory(h);
}

let historyUpdateInterval = null;

function startHistoryTracking() {
  stopHistoryTracking();
  historyUpdateInterval = setInterval(() => {
    if (!selectedItem || videoPlayer.paused || playerModal.classList.contains('hidden')) return;
    const pos = currentStreamOffset + (videoPlayer.currentTime || 0);
    const dur = currentTotalDuration;
    if (pos > 30) {
      updateWatchHistory(
        selectedItem,
        currentPlayingEpisode ? currentPlayingEpisode.season : null,
        currentPlayingEpisode ? currentPlayingEpisode.episode : null,
        pos, dur
      );
    }
  }, 30000);
}

function stopHistoryTracking() {
  clearInterval(historyUpdateInterval);
  historyUpdateInterval = null;
}

// Context Menu State (Apple TV Action Sheet)
let contextMenuItem = null;
let contextMenuTargetId = null;
const contextMenuModal = document.getElementById('context-menu-modal');
const contextPoster = document.getElementById('context-poster');
const contextTitle = document.getElementById('context-title');
const contextSubtitle = document.getElementById('context-subtitle');
const ctxResumeBtn = document.getElementById('ctx-resume');
const ctxDetailsBtn = document.getElementById('ctx-details');
const ctxStartOverBtn = document.getElementById('ctx-start-over');
const ctxRecommendBtn = document.getElementById('ctx-recommend');
const ctxDontRecBtn = document.getElementById('ctx-dontrec');
const ctxRemoveBtn = document.getElementById('ctx-remove');
const ctxCloseBtn = document.getElementById('ctx-close');

function showContextMenu(item, triggerEl) {
  if (!item || !item.id) return;
  // Never show context menu if details modal or video player is currently open!
  if (detailsModal && !detailsModal.classList.contains('hidden')) return;
  if (playerModal && !playerModal.classList.contains('hidden')) return;

  contextMenuItem = item;
  contextMenuTargetId = item.id;

  if (contextPoster) contextPoster.src = item.poster || '';
  if (contextTitle) contextTitle.textContent = item.name || item.title || 'Movie';

  const h = getHistory();
  const histEntry = h[item.id];
  const isCW = histEntry && histEntry.position > 60 && histEntry.position < (histEntry.duration || 7200) * 0.92;

  if (contextSubtitle) {
    const yr = item.releaseInfo || item.year || '';
    const rating = item.imdbRating ? `★ ${item.imdbRating}` : '';
    const parts = [yr, item.type === 'series' ? 'TV Series' : 'Movie', rating].filter(Boolean);
    contextSubtitle.textContent = parts.join(' • ') || 'StreamBravia Cinema';
  }

  // Show "Remove from Continue Watching" only when item is in continue watching
  if (ctxRemoveBtn) {
    if (isCW) {
      ctxRemoveBtn.classList.remove('hidden');
    } else {
      ctxRemoveBtn.classList.add('hidden');
    }
  }

  if (contextMenuModal) {
    contextMenuModal.classList.remove('hidden');
    setTimeout(() => {
      if (ctxResumeBtn) ctxResumeBtn.focus();
    }, 40);
  }
}

function hideContextMenu() {
  if (contextMenuModal) contextMenuModal.classList.add('hidden');
  if (contextMenuTargetId) {
    const el = document.querySelector(`[data-id="${contextMenuTargetId}"]`);
    if (el) el.focus();
  }
  contextMenuTargetId = null;
  contextMenuItem = null;
}

// Universal Context Menu Attachment for ANY card (TV D-Pad Hold / Menu Key / Pointer)
function attachCardContextMenu(card, getItem) {
  let longPressed = false;
  let timer = null;

  card.addEventListener('pointerdown', (e) => {
    longPressed = false;
    clearTimeout(timer);
    timer = setTimeout(() => {
      longPressed = true;
      showContextMenu(getItem(), card);
    }, 750);
  });
  const cancel = () => { clearTimeout(timer); };
  card.addEventListener('pointerup', cancel);
  card.addEventListener('pointercancel', cancel);
  card.addEventListener('pointerleave', cancel);

  card.addEventListener('keydown', (e) => {
    const isEnter = e.key === 'Enter' || e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 66 || e.key === 'Select';
    if (isEnter) {
      if (!timer) {
        longPressed = false;
        timer = setTimeout(() => {
          longPressed = true;
          showContextMenu(getItem(), card);
        }, 750);
      }
    } else if (e.key === 'Menu' || e.keyCode === 82 || e.key === 'm' || e.key === 'M') {
      clearTimeout(timer);
      e.preventDefault();
      e.stopPropagation();
      showContextMenu(getItem(), card);
    }
  });

  card.addEventListener('keyup', (e) => {
    const isEnter = e.key === 'Enter' || e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 66 || e.key === 'Select';
    if (isEnter) {
      clearTimeout(timer);
      timer = null;
      if (longPressed) {
        e.preventDefault();
        e.stopPropagation();
        setTimeout(() => { longPressed = false; }, 120);
      }
    }
  });
}

// Universal Hold Cancellation
function cancelAllHoldTimers() {}

// Continue Watching Row
function renderContinueWatchingRow() {
  const section = document.getElementById('continue-watching-section');
  const row = document.getElementById('continue-watching-row');
  if (!section || !row) return;

  const h = getHistory();
  const items = Object.values(h)
    .filter(e => e.position > 60 && e.position < (e.duration || 7200) * 0.92 && !e.doNotRecommend)
    .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
    .slice(0, 20);

  if (items.length === 0) {
    section.style.display = 'none';
    return;
  }

  section.style.display = 'block';
  row.innerHTML = '';

  items.forEach(entry => {
    const dur = entry.duration || 7200;
    const pct = Math.min(100, Math.max(1, Math.round((entry.position / dur) * 100)));
    const card = document.createElement('div');
    card.className = 'cw-card focusable';
    card.tabIndex = 0;
    card.dataset.historyId = entry.id;
    card.dataset.id = entry.id;

    const epLabel = entry.type === 'series' && entry.season
      ? `S${entry.season}:E${entry.episode || 1}`
      : '';

    card.innerHTML = `
      <div class="cw-poster-wrapper">
        <img class="cw-poster" src="${entry.poster || ''}" alt="${escapeHtml(entry.name)}" loading="lazy">
        ${epLabel ? `<span class="cw-ep-badge">${epLabel}</span>` : ''}
        <div class="cw-progress-bar"><div class="cw-progress-fill" style="width:${pct}%"></div></div>
      </div>
      <div class="cw-info">
        <div class="cw-title">${escapeHtml(entry.name)}</div>
        <div class="cw-meta">${pct}% watched</div>
      </div>
    `;

    // Click: open & resume
    card.addEventListener('click', (e) => {
      openDetails({ id: entry.id, name: entry.name, type: entry.type, poster: entry.poster, year: entry.year }, false);
    });

    // Attach Apple TV Action Sheet Context Menu (hold Enter / press Menu)
    attachCardContextMenu(card, () => ({
      id: entry.id,
      name: entry.name,
      type: entry.type,
      poster: entry.poster,
      year: entry.year,
      imdbRating: entry.rating
    }));

    row.appendChild(card);
  });
}

// Apple TV Context Menu Action Handlers
ctxResumeBtn?.addEventListener('click', () => {
  const item = contextMenuItem;
  hideContextMenu();
  if (item) {
    openDetails(item, true);
  }
});

ctxDetailsBtn?.addEventListener('click', () => {
  const item = contextMenuItem;
  hideContextMenu();
  if (item) {
    openDetails(item, false);
  }
});

ctxStartOverBtn?.addEventListener('click', () => {
  const id = contextMenuTargetId;
  const item = contextMenuItem;
  hideContextMenu();
  if (id) {
    const h = getHistory();
    if (h[id]) {
      h[id].position = 0;
      saveHistory(h);
    }
    fetch('/api/history/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: id, position: 0 })
    }).catch(() => {});
  }
  if (item) {
    openDetails(item, true);
  }
});

ctxRecommendBtn?.addEventListener('click', () => {
  const id = contextMenuTargetId;
  const item = contextMenuItem;
  hideContextMenu();
  if (!id) return;
  const h = getHistory();
  if (!h[id] && item) {
    h[id] = { id: id, name: item.name || '', poster: item.poster || '', type: item.type || 'movie' };
  }
  if (h[id]) {
    h[id].reaction = 1;
    h[id].doNotRecommend = false;
    saveHistory(h);
  }
  fetch('/api/history/reaction', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id, reaction: 1, name: item?.name || '', type: item?.type || 'movie', poster: item?.poster || '' })
  }).catch(() => {});
  showToast('Liked! We will recommend more like this');
  renderRecommendationsRow();
});

ctxDontRecBtn?.addEventListener('click', () => {
  const id = contextMenuTargetId;
  const item = contextMenuItem;
  hideContextMenu();
  if (!id) return;
  const h = getHistory();
  if (!h[id] && item) {
    h[id] = { id: id, name: item.name || '', poster: item.poster || '', type: item.type || 'movie' };
  }
  if (h[id]) {
    h[id].reaction = -1;
    h[id].doNotRecommend = true;
    saveHistory(h);
  }
  fetch('/api/history/reaction', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id, reaction: -1, name: item?.name || '', type: item?.type || 'movie', poster: item?.poster || '' })
  }).catch(() => {});
  showToast('Marked as Not Interested');
  renderContinueWatchingRow();
  renderRecommendationsRow();
});

ctxRemoveBtn?.addEventListener('click', () => {
  const id = contextMenuTargetId;
  hideContextMenu();
  if (!id) return;
  const h = getHistory();
  delete h[id];
  saveHistory(h);
  fetch('/api/history/remove', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id })
  }).catch(() => {});
  renderContinueWatchingRow();
  showToast('Removed from Continue Watching');
});

ctxCloseBtn?.addEventListener('click', () => {
  hideContextMenu();
});

contextMenuModal?.addEventListener('click', (e) => {
  if (e.target === contextMenuModal) {
    hideContextMenu();
  }
});

// Recommendations Row (Powered by Minimal Watch History Repository)
async function renderRecommendationsRow() {
  const section = document.getElementById('recommendations-section');
  const row = document.getElementById('recommendations-row');
  if (!section || !row) return;

  try {
    let recs = [];
    try {
      const recRes = await fetch('/api/recommendations');
      const recData = await recRes.json();
      if (recData && recData.status === 'ok' && Array.isArray(recData.items) && recData.items.length > 0) {
        recs = recData.items;
      }
    } catch (e) {
      // Fallback to local heuristic
    }

    if (recs.length === 0) {
      const h = getHistory();
      const items = Object.values(h).filter(e => !e.doNotRecommend);
      if (items.length === 0) { section.style.display = 'none'; return; }
      const genreCount = {};
      items.forEach(e => {
        const g = e.type === 'series' ? 'Drama' : 'Action';
        genreCount[g] = (genreCount[g] || 0) + 1;
      });
      const topGenre = Object.entries(genreCount).sort((a, b) => b[1] - a[1])[0]?.[0] || 'Action';
      const res = await fetch(`/api/catalog?type=movie&genre=${encodeURIComponent(topGenre)}&skip=0`);
      const data = await res.json();
      const allItems = data.items || [];
      const historyIds = new Set(Object.keys(h));
      recs = allItems.filter(i => !historyIds.has(i.id)).slice(0, 15);
    }

    if (recs.length === 0) { section.style.display = 'none'; return; }
    section.style.display = 'block';
    row.innerHTML = '';

    recs.forEach(item => {
      const card = document.createElement('div');
      card.className = 'rec-card focusable';
      card.tabIndex = 0;
      card.dataset.id = item.id;
      card.innerHTML = `
        <img class="rec-poster" src="${item.poster || ''}" alt="${escapeHtml(item.name)}" loading="lazy">
        <div class="rec-info">
          <div class="rec-title">${escapeHtml(item.name)}</div>
          <div class="rec-year">${item.releaseInfo || item.year || ''}</div>
        </div>
      `;
      card.addEventListener('click', () => openDetails(item, false));
      attachCardContextMenu(card, () => item);
      row.appendChild(card);
    });
  } catch (e) {
    section.style.display = 'none';
  }
}

// Settings Elements
const filterAdultCheck = document.getElementById('filter-adult-check');
const filterParodiesCheck = document.getElementById('filter-parodies-check');

// ---------------- Unified Client Logger ----------------

function logClient(level, message, data = null) {
  console.log(`[${level.toUpperCase()}] [UI]`, message, data || '');
}

// ---------------- Initialization ----------------

document.addEventListener('DOMContentLoaded', () => {
  logClient('INFO', 'StreamBravia UI initialized on client');
  loadDeviceCapabilities();
  loadCatalog(currentType, currentGenre);
  loadSettings();
  setupEventListeners();
  setupFilterButtons();
  setupDpadNavigation();
  setupVideoPlayerEvents();
  renderContinueWatchingRow();
  renderRecommendationsRow();
});

function setupEventListeners() {
  // Navigation tabs (Movies / Series)
  document.querySelectorAll('.nav-btn[data-type]').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      if (regionalFilterRow) regionalFilterRow.classList.add('hidden');
      currentType = btn.dataset.type;
      currentGenre = '';
      if (searchInput) searchInput.value = '';
      logClient('INFO', `Switched catalogue to: ${currentType}`);
      loadCatalog(currentType, currentGenre, currentRegionalLang, currentYear);
    });
  });

  // Regional Navigation Tab
  const btnRegional = document.getElementById('btn-regional');
  if (btnRegional) {
    btnRegional.addEventListener('click', () => {
      document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
      btnRegional.classList.add('active');
      if (regionalFilterRow) regionalFilterRow.classList.remove('hidden');
      currentType = 'movie';
      currentGenre = 'Regional';
      if (searchInput) searchInput.value = '';
      logClient('INFO', `Switched catalogue to Regional (${currentRegionalLang})`);
      loadCatalog(currentType, currentGenre, currentRegionalLang, currentYear);
    });
  }

  // Regional Language Filter Pills
  if (regionalFilterRow) {
    regionalFilterRow.querySelectorAll('.filter-pill').forEach(pill => {
      pill.addEventListener('click', () => {
        regionalFilterRow.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
        pill.classList.add('active');
        currentRegionalLang = pill.dataset.lang || 'all';
        logClient('INFO', `Filtered Regional by language: ${currentRegionalLang}`);
        loadCatalog(currentType, currentGenre, currentRegionalLang, currentYear);
      });
    });
  }

  // Genre Menu Toggle
  btnGenres.addEventListener('click', (e) => {
    e.stopPropagation();
    genreMenu.classList.toggle('hidden');
    if (!genreMenu.classList.contains('hidden')) {
      const firstGenre = genreMenu.querySelector('.genre-item');
      if (firstGenre) firstGenre.focus();
    }
  });

  document.querySelectorAll('.genre-item').forEach(item => {
    item.addEventListener('click', () => {
      currentGenre = item.dataset.genre;
      genreMenu.classList.add('hidden');
      logClient('INFO', `Filter genre: ${currentGenre || 'All'}`);
      loadCatalog(currentType, currentGenre, currentRegionalLang, currentYear);
    });
  });

  document.addEventListener('click', (e) => {
    if (!genreMenu.contains(e.target) && e.target !== btnGenres) {
      genreMenu.classList.add('hidden');
    }
  });

  // Populate Dynamic Year Sub-Filter Pills (Similar to YTS Filter Bar)
  function populateYearPills() {
    if (!yearPillsContainer) return;
    const thisYear = new Date().getFullYear();
    yearPillsContainer.innerHTML = '';
    const yearOptions = [
      { label: 'All', val: '' },
      { label: String(thisYear), val: String(thisYear) },
      { label: String(thisYear - 1), val: String(thisYear - 1) },
      { label: String(thisYear - 2), val: String(thisYear - 2) },
      { label: String(thisYear - 3), val: String(thisYear - 3) },
      { label: String(thisYear - 4), val: String(thisYear - 4) },
      { label: '2010s', val: '2010s' },
      { label: '2000s', val: '2000s' },
      { label: 'Classics', val: 'classics' }
    ];

    yearOptions.forEach(opt => {
      const btn = document.createElement('button');
      btn.className = 'filter-pill focusable' + (currentYear === opt.val ? ' active' : '');
      btn.dataset.year = opt.val;
      btn.textContent = opt.label;
      btn.tabIndex = 0;
      btn.addEventListener('click', () => {
        yearPillsContainer.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        currentYear = opt.val;
        logClient('INFO', `Sub-filter year: ${opt.label}`);
        loadCatalog(currentType, currentGenre, currentRegionalLang, currentYear);
      });
      yearPillsContainer.appendChild(btn);
    });
  }
  populateYearPills();

  // Search & Voice Search
  if (voiceSearchBtn) {
    voiceSearchBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (window.BraviaNative && typeof window.BraviaNative.startVoiceSearch === 'function') {
        window.BraviaNative.startVoiceSearch();
      } else {
        showToast('Hold the voice/mic button on your remote to speak');
      }
    });
  }

  searchBtn.addEventListener('click', executeSearch);
  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') executeSearch();
  });
  searchInput.addEventListener('input', () => {
    if (!searchInput.value.trim()) {
      loadCatalog(currentType, currentGenre);
    }
  });
  searchInput.addEventListener('focus', () => {
    // Keep placeholder clean for direct user queries
  });

  // Hero Actions: Clicking "Watch Now" starts in-app playback automatically
  heroPlayBtn.addEventListener('click', () => {
    if (selectedItem && selectedItem.id) {
      logClient('INFO', `Hero "Watch Now" clicked for: ${selectedItem.name}`);
      openDetails(selectedItem, true);
    } else {
      showToast('Catalog is loading, please wait a moment...');
    }
  });
  heroDetailsBtn.addEventListener('click', () => {
    if (selectedItem && selectedItem.id) {
      openDetails(selectedItem, false);
    } else {
      showToast('Catalog is loading, please wait a moment...');
    }
  });

  // Modal Closers & Openers
  modalCloseBtn.addEventListener('click', closeDetailsModal);
  settingsCloseBtn.addEventListener('click', closeSettingsModal);
  settingsBtn.addEventListener('click', openSettingsModal);

  // Logs Modal Controls
  if (btnLogs) {
    btnLogs.addEventListener('click', openLogsModal);
  }
  const btnOpenLogsSettings = document.getElementById('btn-open-logs-from-settings');
  if (btnOpenLogsSettings) {
    btnOpenLogsSettings.addEventListener('click', () => {
      settingsModal.classList.add('hidden');
      openLogsModal();
    });
  }
  if (logsCloseBtn) {
    logsCloseBtn.addEventListener('click', closeLogsModal);
  }
  if (logsRefreshBtn) {
    logsRefreshBtn.addEventListener('click', fetchAndRenderLogs);
  }
  if (logsClearBtn) {
    logsClearBtn.addEventListener('click', () => {
      logsTerminal.innerHTML = '<div class="log-line">Logs cleared from view.</div>';
    });
  }

  // Settings Save
  saveSettingsBtn.addEventListener('click', saveSettings);

  // OTA Software Update Listeners
  if (updateCloseBtn) {
    updateCloseBtn.addEventListener('click', hideUpdateModal);
  }
  if (btnUpdateLater) {
    btnUpdateLater.addEventListener('click', hideUpdateModal);
  }
  if (btnUpdateInstall) {
    btnUpdateInstall.addEventListener('click', startAppUpdate);
  }
  if (btnSettingsCheckUpdate) {
    btnSettingsCheckUpdate.addEventListener('click', () => checkForAppUpdates(true));
  }
}

// ---------------- OTA Over-The-Air Update System ----------------

function showUpdateModal(info) {
  if (!info || !info.has_update) return;
  currentActiveUpdate = info;

  if (updateOldVersion) updateOldVersion.textContent = 'v' + (info.current_version || '1.0.0');
  if (updateNewVersion) updateNewVersion.textContent = 'v' + (info.latest_version || '1.0.1');
  if (updateSizeBadge) updateSizeBadge.textContent = info.apk_size_mb || (info.apk_size ? Math.round(info.apk_size / 1048576) + ' MB' : 'APK');
  if (updateReleaseTitle) updateReleaseTitle.textContent = info.release_title || 'What\'s New in this Version';
  if (updateChangelog) updateChangelog.textContent = info.release_notes || 'Performance enhancements, playback improvements, and bug fixes.';

  if (updateProgressContainer) updateProgressContainer.classList.add('hidden');
  if (updateProgressBar) updateProgressBar.style.width = '0%';
  if (updateProgressText) updateProgressText.textContent = 'Downloading update from GitHub...';
  if (updateProgressPct) updateProgressPct.textContent = '0%';

  if (btnUpdateInstall) {
    btnUpdateInstall.disabled = false;
    btnUpdateInstall.textContent = '⬇️ Download & Install Now';
  }
  if (btnUpdateLater) btnUpdateLater.style.display = 'inline-block';

  if (updateModal) {
    updateModal.classList.remove('hidden');
    setTimeout(() => {
      if (btnUpdateInstall) btnUpdateInstall.focus();
    }, 150);
  }
}

function hideUpdateModal() {
  if (updateModal) updateModal.classList.add('hidden');
  if (updatePollingTimer) {
    clearInterval(updatePollingTimer);
    updatePollingTimer = null;
  }
}

function startAppUpdate() {
  if (!currentActiveUpdate || !currentActiveUpdate.apk_url) {
    showToast('Error: No valid update download URL found');
    return;
  }

  if (updateProgressContainer) updateProgressContainer.classList.remove('hidden');
  if (btnUpdateInstall) {
    btnUpdateInstall.disabled = true;
    btnUpdateInstall.textContent = '⏳ Downloading Update...';
  }
  if (btnUpdateLater) btnUpdateLater.style.display = 'none';

  logClient('INFO', `Starting OTA download: ${currentActiveUpdate.apk_url}`);

  if (window.BraviaNative && typeof window.BraviaNative.downloadAndInstallUpdate === 'function') {
    window.BraviaNative.downloadAndInstallUpdate(
      currentActiveUpdate.apk_url,
      currentActiveUpdate.apk_file_name || 'StreamBravia-TV.apk'
    );
  } else {
    fetch(`/api/update/download?apk_url=${encodeURIComponent(currentActiveUpdate.apk_url)}&apk_name=${encodeURIComponent(currentActiveUpdate.apk_file_name || 'StreamBravia-TV.apk')}`)
      .then(res => res.json())
      .then(() => {
        pollUpdateProgress();
      })
      .catch(err => {
        window.onUpdateDownloadError(err.message);
      });
  }
}

function pollUpdateProgress() {
  if (updatePollingTimer) clearInterval(updatePollingTimer);
  updatePollingTimer = setInterval(async () => {
    try {
      const res = await fetch('/api/update/progress');
      const data = await res.json();
      if (data.status === 'downloading') {
        window.onUpdateDownloadProgress(data.percent, data.bytes_read, data.total_bytes);
      } else if (data.status === 'completed') {
        clearInterval(updatePollingTimer);
        updatePollingTimer = null;
        window.onUpdateDownloadCompleted();
      } else if (data.status === 'error') {
        clearInterval(updatePollingTimer);
        updatePollingTimer = null;
        window.onUpdateDownloadError(data.error || 'Download failed');
      }
    } catch (e) {}
  }, 400);
}

window.showUpdateModal = showUpdateModal;

window.onUpdateDownloadProgress = function(percent, bytesRead, totalBytes) {
  const pct = Math.max(0, Math.min(100, percent || 0));
  if (updateProgressBar) updateProgressBar.style.width = pct + '%';
  if (updateProgressPct) updateProgressPct.textContent = pct + '%';
  if (updateProgressText) {
    if (totalBytes > 0) {
      const readMb = (bytesRead / (1024 * 1024)).toFixed(1);
      const totalMb = (totalBytes / (1024 * 1024)).toFixed(1);
      updateProgressText.textContent = `Downloading: ${readMb} MB / ${totalMb} MB (${pct}%)`;
    } else {
      updateProgressText.textContent = `Downloading update from GitHub... (${pct}%)`;
    }
  }
};

window.onUpdateDownloadCompleted = function() {
  if (updateProgressBar) updateProgressBar.style.width = '100%';
  if (updateProgressPct) updateProgressPct.textContent = '100%';
  if (updateProgressText) updateProgressText.textContent = '✓ Download completed! Launching Android package installer...';
  if (btnUpdateInstall) btnUpdateInstall.textContent = '✓ Installing Update...';
  showToast('Update ready — follow on-screen TV prompt to install');
};

window.onUpdateDownloadError = function(error) {
  if (updateProgressText) updateProgressText.textContent = '❌ ' + (error || 'Failed to download update');
  if (btnUpdateInstall) {
    btnUpdateInstall.disabled = false;
    btnUpdateInstall.textContent = '🔄 Retry Download';
  }
  if (btnUpdateLater) btnUpdateLater.style.display = 'inline-block';
  showToast('Update download failed: ' + error);
};

window.onUpdateCheckResult = function(data, userInitiated) {
  if (settingsUpdateStatus) {
    if (data.has_update) {
      settingsUpdateStatus.innerHTML = `<span style="color:var(--accent-orange-bright); font-weight:700;">🚀 New update v${data.latest_version} available!</span>`;
    } else if (data.error) {
      settingsUpdateStatus.innerHTML = `<span style="color:#ef4444;">⚠ ${escapeHtml(data.error)}</span>`;
    } else {
      settingsUpdateStatus.innerHTML = `<span style="color:#10b981;">✓ Up to date (v${data.current_version || '1.0.0'})</span>`;
    }
  }

  if (data.has_update) {
    showUpdateModal(data);
  } else if (userInitiated) {
    showToast(data.message || `StreamBravia v${data.current_version || '1.0.0'} is up to date.`);
  }
};

async function checkForAppUpdates(userInitiated = false) {
  if (settingsUpdateStatus) {
    settingsUpdateStatus.innerHTML = '<span style="color:var(--accent-gold);">Checking GitHub releases...</span>';
  }

  if (window.BraviaNative && typeof window.BraviaNative.checkForUpdates === 'function') {
    window.BraviaNative.checkForUpdates(userInitiated);
  } else {
    try {
      const res = await fetch('/api/update/check');
      const data = await res.json();
      window.onUpdateCheckResult(data, userInitiated);
    } catch (e) {
      if (settingsUpdateStatus) {
        settingsUpdateStatus.innerHTML = `<span style="color:#ef4444;">Error checking update: ${e.message}</span>`;
      }
      if (userInitiated) {
        showToast('Update check failed: ' + e.message);
      }
    }
  }
}

const LANG_NAMES = {
  'all': 'All Languages',
  'eng': 'English', 'en': 'English',
  'spa': 'Spanish', 'es': 'Spanish',
  'fre': 'French', 'fra': 'French', 'fr': 'French',
  'ger': 'German', 'deu': 'German', 'de': 'German',
  'ita': 'Italian', 'it': 'Italian',
  'pob': 'Portuguese (Brasil)',
  'por': 'Portuguese (Portugal)', 'pt': 'Portuguese',
  'rus': 'Russian', 'ru': 'Russian',
  'hin': 'Hindi', 'hi': 'Hindi',
  'tam': 'Tamil', 'ta': 'Tamil',
  'tel': 'Telugu', 'te': 'Telugu',
  'mal': 'Malayalam', 'ml': 'Malayalam',
  'kan': 'Kannada', 'kn': 'Kannada',
  'ara': 'Arabic', 'ar': 'Arabic',
  'chi': 'Chinese', 'zho': 'Chinese', 'zh': 'Chinese',
  'jpn': 'Japanese', 'ja': 'Japanese',
  'kor': 'Korean', 'ko': 'Korean',
  'tur': 'Turkish', 'tr': 'Turkish',
  'pol': 'Polish', 'pl': 'Polish',
  'dut': 'Dutch', 'nld': 'Dutch', 'nl': 'Dutch'
};

let deviceCapabilities = {
  max_resolution: '1080p',
  supports_4k: false,
  supports_8k: false
};

async function loadDeviceCapabilities() {
  try {
    const res = await fetch('/api/device_capabilities');
    const data = await res.json();
    if (data && data.status === 'ok') {
      deviceCapabilities = data;
      logClient('INFO', `Hardware capabilities: ${data.max_resolution} (4K=${data.supports_4k}, 8K=${data.supports_8k}, ${data.model || ''})`);
      updateDeviceUI();
    }
  } catch (e) {
    logClient('WARN', `Could not query device capabilities: ${e.message}`);
  }
}

function updateDeviceUI() {
  const filter4k = document.getElementById('filter-btn-4k');
  const tbFilter4k = document.getElementById('tb-filter-btn-4k');
  if (deviceCapabilities.supports_4k) {
    if (filter4k) filter4k.classList.remove('hidden');
    if (tbFilter4k) tbFilter4k.classList.remove('hidden');
  } else {
    if (filter4k) filter4k.classList.add('hidden');
    if (tbFilter4k) tbFilter4k.classList.add('hidden');
  }

  const devBadge = document.getElementById('settings-device-capability');
  if (devBadge) {
    devBadge.textContent = `${deviceCapabilities.model || 'Device'} • ${deviceCapabilities.max_resolution} Hardware Decode (${deviceCapabilities.supports_4k ? '4K Ultra HD' : '1080p Full HD'})`;
  }
}

function parseRuntimeSeconds(runtime) {
  if (!runtime) return 7200;
  if (typeof runtime === 'number') {
    return runtime > 500 ? runtime : runtime * 60;
  }
  const str = String(runtime).toLowerCase();
  const minMatch = str.match(/(\d+)\s*min/);
  if (minMatch) return parseInt(minMatch[1], 10) * 60;
  const hrMatch = str.match(/(\d+)\s*h(?:our)?s?/);
  const minMatch2 = str.match(/(\d+)\s*m(?:in)?s?/);
  if (hrMatch) {
    const hours = parseInt(hrMatch[1], 10);
    const mins = minMatch2 ? parseInt(minMatch2[1], 10) : 0;
    return hours * 3600 + mins * 60;
  }
  const numMatch = str.match(/\d+/);
  if (numMatch) {
    const n = parseInt(numMatch[0], 10);
    return n < 30 ? n * 3600 : n * 60;
  }
  return 7200;
}

function formatTime(seconds) {
  if (!isFinite(seconds) || isNaN(seconds) || seconds < 0) return '00:00:00';
  const totalSec = Math.floor(seconds);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
}

function formatDurationDelta(seconds) {
  const abs = Math.abs(seconds);
  if (abs < 60) return `${abs}s`;
  const m = Math.floor(abs / 60);
  const s = abs % 60;
  return s > 0 ? `${m}m ${s}s` : `${m}m`;
}

function enterFullscreen(elem = playerModal) {
  try {
    if (document.fullscreenElement) return;
    const req = elem.requestFullscreen || elem.webkitRequestFullscreen || elem.mozRequestFullScreen || elem.msRequestFullscreen;
    if (req) {
      req.call(elem).catch(() => {});
    }
  } catch (e) {}
}


function resetInactivityTimer(wakeUp = true) {
  if (playerModal.classList.contains('hidden')) return;
  if (wakeUp) {
    playerModal.classList.remove('idle');
  }
  clearTimeout(inactivityTimer);
  inactivityTimer = setTimeout(() => {
    const isSubOpen = subtitleModal && !subtitleModal.classList.contains('hidden');
    const isAudioOpen = audioModal && !audioModal.classList.contains('hidden');
    if (!videoPlayer.paused && !playerModal.classList.contains('hidden') && !isSubOpen && !isAudioOpen) {
      playerModal.classList.add('idle');
      if (document.activeElement && (document.activeElement.closest('.tv-controls-overlay') || document.activeElement.classList.contains('focusable'))) {
        document.activeElement.blur();
      }
    }
  }, 4000);
}

function updatePlayPauseUI(isPaused) {
  if (svgPlay && svgPause) {
    if (isPaused) {
      svgPlay.classList.remove('hidden');
      svgPause.classList.add('hidden');
      if (btnPlayPause) btnPlayPause.title = 'Play (Space / Enter)';
    } else {
      svgPlay.classList.add('hidden');
      svgPause.classList.remove('hidden');
      if (btnPlayPause) btnPlayPause.title = 'Pause (Space / Enter)';
    }
  }
}

let userExplicitlyPaused = false;

function togglePlayPause() {
  if (videoPlayer.paused) {
    userExplicitlyPaused = false;
    videoPlayer.play().then(() => {
      updatePlayPauseUI(false);
      showToast('▶ Playing');
      resetInactivityTimer();
    }).catch(e => {
      logClient('WARN', `Play error: ${e.message}`);
    });
  } else {
    userExplicitlyPaused = true;
    videoPlayer.pause();
    updatePlayPauseUI(true);
    showToast('⏸ Paused');
    playerModal.classList.remove('idle');
  }
}

function toggleHud() {
  playerStreamHud.classList.toggle('hidden');
}

function updateSeekBar() {
  if (isSeeking) return;

  if (isFinite(videoPlayer.duration) && videoPlayer.duration > 60) {
    currentTotalDuration = Math.max(currentTotalDuration, videoPlayer.duration);
  }

  const cur = currentStreamOffset + (videoPlayer.currentTime || 0);
  seekCurrentTime.textContent = formatTime(cur);
  seekTotalTime.textContent = formatTime(currentTotalDuration);

  const pct = Math.max(0, Math.min(100, (cur / currentTotalDuration) * 100));
  tvSeekProgress.style.width = `${pct}%`;
  tvSeekThumb.style.left = `${pct}%`;

  if (videoPlayer.buffered && videoPlayer.buffered.length > 0) {
    try {
      const end = currentStreamOffset + videoPlayer.buffered.end(videoPlayer.buffered.length - 1);
      const bufPct = Math.max(0, Math.min(100, (end / currentTotalDuration) * 100));
      tvSeekBuffered.style.width = `${bufPct}%`;
    } catch (e) {}
  }
}

function handleDpadSeek(direction) {
  isSeeking = true;
  const now = Date.now();
  if (now - lastSeekPressTime < 650) {
    seekPressCount++;
  } else {
    seekPressCount = 1;
    pendingSeekTarget = currentStreamOffset + (videoPlayer.currentTime || 0);
  }
  lastSeekPressTime = now;

  let step = 10;
  if (seekPressCount >= 6) step = 300;      // 5 min rapid skip
  else if (seekPressCount >= 4) step = 120; // 2 min
  else if (seekPressCount >= 3) step = 60;  // 1 min
  else if (seekPressCount >= 2) step = 30;  // 30 sec
  else step = 10;                           // 10 sec

  pendingSeekTarget = Math.max(0, Math.min(currentTotalDuration, pendingSeekTarget + direction * step));
  const currentActual = currentStreamOffset + (videoPlayer.currentTime || 0);
  const totalDelta = pendingSeekTarget - currentActual;

  // Immediate visual feedback on Seek Bar
  const pct = Math.max(0, Math.min(100, (pendingSeekTarget / currentTotalDuration) * 100));
  tvSeekProgress.style.width = `${pct}%`;
  tvSeekThumb.style.left = `${pct}%`;
  seekCurrentTime.textContent = formatTime(pendingSeekTarget);

  // Show Center Pill
  seekPill.classList.remove('hidden');
  seekPillIcon.textContent = direction > 0 ? '⏩' : '⏪';
  seekPillDelta.textContent = (totalDelta >= 0 ? '+' : '') + formatDurationDelta(totalDelta);
  seekPillTime.textContent = `${formatTime(pendingSeekTarget)} / ${formatTime(currentTotalDuration)}`;

  // Seeking mode hides distracting top bar / buttons
  playerModal.classList.add('seeking');
  playerModal.classList.remove('idle');
  clearTimeout(inactivityTimer);

  // Debounce seek commit — seek natively on videoPlayer timeline without breaking stream pipeline
  clearTimeout(seekCommitTimeout);
  seekCommitTimeout = setTimeout(() => {
    const targetSec = Math.max(0, Math.floor(pendingSeekTarget));
    seekPill.classList.add('hidden');
    playerModal.classList.remove('seeking');
    isSeeking = false;
    seekPressCount = 0;

    if (videoPlayer && videoPlayer.src) {
      logClient('INFO', `Seeking directly to ${targetSec}s on native timeline`);
      videoPlayer.currentTime = targetSec;
      updateSeekBar();
    }
    resetInactivityTimer();
  }, 400);
}

function setupVideoPlayerEvents() {
  // Activity reset for 4-second auto-hide (TV remote key presses)
  ['keydown', 'touchstart'].forEach(evt => {
    window.addEventListener(evt, () => {
      if (!playerModal.classList.contains('hidden')) {
        resetInactivityTimer();
      }
    });
  });

  // Click on video toggles play/pause or wakes up controls
  videoPlayer.addEventListener('click', (e) => {
    e.stopPropagation();
    if (playerModal.classList.contains('idle')) {
      resetInactivityTimer();
      return;
    }
    togglePlayPause();
  });

  let bufferDebounceTimer = null;

  videoPlayer.addEventListener('playing', () => {
    clearTimeout(bufferDebounceTimer);
    logClient('INFO', 'In-App video playback started');
    isBuffering = false;
    playerModal.classList.remove('buffering');
    hidePlayerSpinner();
    updatePlayPauseUI(false);
    // If controls are already idle (hidden), keep them hidden; do not pop up controls on background playing events
    if (!playerModal.classList.contains('idle')) {
      resetInactivityTimer(false);
    }
  });

  videoPlayer.addEventListener('pause', () => {
    updatePlayPauseUI(true);
    playerModal.classList.remove('idle');
  });

  videoPlayer.addEventListener('timeupdate', () => {
    updateSeekBar();
    // When playback time is moving, media frames are rendering — dismiss any buffering overlay immediately
    if (!videoPlayer.paused && !isSeeking) {
      clearTimeout(bufferDebounceTimer);
      if (isBuffering || playerModal.classList.contains('buffering')) {
        isBuffering = false;
        playerModal.classList.remove('buffering');
        hidePlayerSpinner();
      }
    }
  });
  videoPlayer.addEventListener('progress', updateSeekBar);

  videoPlayer.addEventListener('canplay', () => {
    clearTimeout(bufferDebounceTimer);
    isBuffering = false;
    playerModal.classList.remove('buffering');
    hidePlayerSpinner();
    updateSeekBar();
  });

  videoPlayer.addEventListener('waiting', () => {
    // Only show spinner if playback actually halted for lack of data (>2.5s)
    clearTimeout(bufferDebounceTimer);
    bufferDebounceTimer = setTimeout(() => {
      if (!videoPlayer.paused && videoPlayer.readyState < 3) {
        isBuffering = true;
        playerModal.classList.add('buffering');
        showPlayerSpinner('Buffering stream...', 'Receiving incoming video pieces...');
      }
    }, 2500);
  });

  videoPlayer.addEventListener('error', () => {
    const err = videoPlayer.error;
    const msg = err ? `Code ${err.code}: ${err.message}` : 'Playback error';
    logClient('ERROR', 'Video error', { error: msg });
    // Do not continuously reload videoPlayer.src on transient gaps or decoder hiccups
    if (err && err.code === 4) {
      showToast('Media stream format or source unavailable.');
    }
  });

  videoPlayer.addEventListener('ended', () => {
    logClient('INFO', 'Video playback ended');
    if (currentPlayingEpisode && currentPlayingEpisode.nextEp) {
      showToast('Episode finished — Loading next episode in 2 seconds...');
      setTimeout(() => {
        playNextEpisode();
      }, 2000);
    }
  });

  // Seek bar click / scrub
  if (tvSeekBarWrapper) {
    tvSeekBarWrapper.addEventListener('click', (e) => {
      const rect = tvSeekBarWrapper.getBoundingClientRect();
      const clickX = e.clientX - rect.left;
      const ratio = Math.max(0, Math.min(1, clickX / rect.width));
      const targetSec = Math.floor(ratio * currentTotalDuration);
      if (videoPlayer && videoPlayer.src) {
        videoPlayer.currentTime = targetSec;
        updateSeekBar();
      }
      resetInactivityTimer();
    });
  }

  // TV Action Buttons (Icon-Only Mode)
  const btnRestart = document.getElementById('btn-restart');
  if (btnRestart) {
    btnRestart.addEventListener('click', () => {
      if (videoPlayer) {
        videoPlayer.currentTime = 0;
        currentStreamOffset = 0;
        updateSeekBar();
        showToast('↺ Restarted from beginning (00:00:00)');
      }
    });
  }

  if (btnPlayPause) btnPlayPause.addEventListener('click', togglePlayPause);
  if (btnRewind) btnRewind.addEventListener('click', () => handleDpadSeek(-1));
  if (btnForward) btnForward.addEventListener('click', () => handleDpadSeek(1));
  if (playerAudioBtn) playerAudioBtn.addEventListener('click', openAudioModal);
  if (playerSubBtn) playerSubBtn.addEventListener('click', openSubtitleModal);
  if (btnHudToggle) btnHudToggle.addEventListener('click', toggleHud);

  // Audio Modal Listeners
  if (audioCloseBtn) audioCloseBtn.addEventListener('click', closeAudioModal);

  // Subtitle Modal Listeners
  if (subCloseBtn) subCloseBtn.addEventListener('click', closeSubtitleModal);
  if (subLangSelect) subLangSelect.addEventListener('change', filterAndRenderSubtitles);
  if (btnSearchSubs) {
    btnSearchSubs.addEventListener('click', () => {
      const id = selectedItem ? selectedItem.id : (activeResolvedStream?.streamItem?.id || '');
      fetchOnlineSubtitles(id);
    });
  }
  const btnSearchSubsHash = document.getElementById('btn-search-subs-hash');
  if (btnSearchSubsHash) {
    btnSearchSubsHash.addEventListener('click', () => {
      const id = selectedItem ? selectedItem.id : (activeResolvedStream?.streamItem?.id || '');
      fetchOnlineSubtitles(id, activeResolvedStream?.info_hash || '', true);
    });
  }
  const subQueryInput = document.getElementById('sub-search-query-input');
  if (subQueryInput) {
    subQueryInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const id = selectedItem ? selectedItem.id : (activeResolvedStream?.streamItem?.id || '');
        fetchOnlineSubtitles(id);
      }
    });
  }

  setupSubtitleAppearanceControls();
}

// ---------------- Subtitle Appearance & Style (Font Size, Bg) ----------------

let subtitleSyncOffset = 0;

function applySubtitleSyncOffset(delta, isAbsolute = false) {
  if (isAbsolute) {
    subtitleSyncOffset = delta;
  } else {
    subtitleSyncOffset = Math.round((subtitleSyncOffset + delta) * 10) / 10;
  }
  const display = document.getElementById('sub-sync-display');
  if (display) {
    display.textContent = (subtitleSyncOffset > 0 ? '+' : '') + subtitleSyncOffset.toFixed(1) + 's';
  }
  const overlay = document.getElementById('video-subtitles-overlay');
  if (overlay) overlay.dataset.lastCueHtml = '';
  if (typeof videoPlayer?._subCueHandler === 'function') {
    videoPlayer._subCueHandler();
  }
  showToast(`Subtitle Delay: ${(subtitleSyncOffset > 0 ? '+' : '') + subtitleSyncOffset.toFixed(1)}s`);
}

function setupSubtitleAppearanceControls() {
  document.getElementById('sub-sync-minus')?.addEventListener('click', () => applySubtitleSyncOffset(-0.5));
  document.getElementById('sub-sync-plus')?.addEventListener('click', () => applySubtitleSyncOffset(+0.5));
  document.getElementById('sub-sync-reset')?.addEventListener('click', () => applySubtitleSyncOffset(0, true));

  const savedSize = localStorage.getItem('streambravia_sub_size') || '24px';
  const savedBg = localStorage.getItem('streambravia_sub_bg') || 'box';

  applySubtitleStyle(savedSize, savedBg);

  document.querySelectorAll('#sub-font-size-group .sub-style-pill').forEach(btn => {
    if (btn.dataset.size === savedSize) btn.classList.add('active');
    else btn.classList.remove('active');

    btn.addEventListener('click', () => {
      document.querySelectorAll('#sub-font-size-group .sub-style-pill').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const size = btn.dataset.size;
      const currentBg = localStorage.getItem('streambravia_sub_bg') || 'box';
      localStorage.setItem('streambravia_sub_size', size);
      applySubtitleStyle(size, currentBg);
      showToast(`Subtitle Font: ${btn.textContent.trim()}`);
    });
  });

  document.querySelectorAll('#sub-bg-group .sub-style-pill').forEach(btn => {
    if (btn.dataset.bg === savedBg) btn.classList.add('active');
    else btn.classList.remove('active');

    btn.addEventListener('click', () => {
      document.querySelectorAll('#sub-bg-group .sub-style-pill').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const bg = btn.dataset.bg;
      const currentSize = localStorage.getItem('streambravia_sub_size') || '24px';
      localStorage.setItem('streambravia_sub_bg', bg);
      applySubtitleStyle(currentSize, bg);
      showToast(`Subtitle Style: ${btn.textContent.trim()}`);
    });
  });
}

function applySubtitleStyle(size, bg) {
  const root = document.documentElement;
  root.style.setProperty('--sub-font-size', size);

  let bgColor = 'rgba(0, 0, 0, 0.85)';
  let textColor = '#ffffff';
  let textShadow = 'none';

  if (bg === 'box') {
    bgColor = 'rgba(0, 0, 0, 0.85)';
    textColor = '#ffffff';
    textShadow = 'none';
  } else if (bg === 'translucent') {
    bgColor = 'rgba(0, 0, 0, 0.45)';
    textColor = '#ffffff';
    textShadow = '0 2px 4px rgba(0, 0, 0, 0.9)';
  } else if (bg === 'outline') {
    bgColor = 'transparent';
    textColor = '#ffffff';
    textShadow = '-2px -2px 0 #000, 2px -2px 0 #000, -2px 2px 0 #000, 2px 2px 0 #000, 0 0 8px rgba(0,0,0,0.9)';
  } else if (bg === 'yellow') {
    bgColor = 'rgba(0, 0, 0, 0.85)';
    textColor = '#fde047';
    textShadow = 'none';
  }

  root.style.setProperty('--sub-bg-color', bgColor);
  root.style.setProperty('--sub-text-color', textColor);
  root.style.setProperty('--sub-text-shadow', textShadow);

  const preview = document.getElementById('sub-preview-cue');
  if (preview) {
    preview.style.fontSize = size;
    preview.style.backgroundColor = bgColor;
    preview.style.color = textColor;
    preview.style.textShadow = textShadow;
  }

  const liveCue = document.querySelector('.video-subtitles-cue-text');
  if (liveCue) {
    liveCue.style.fontSize = size;
    liveCue.style.backgroundColor = bgColor;
    liveCue.style.color = textColor;
    liveCue.style.textShadow = textShadow;
  }
}

function setupSubtitleTrackCueSync(trackEl) {
  const overlay = document.getElementById('video-subtitles-overlay');
  if (!overlay || !trackEl) return;

  const track = trackEl.track;
  if (!track) return;
  track.mode = 'hidden'; // Ensure browser does not double-render unstyled native subtitles

  const renderCues = () => {
    let cuesToRender = [];
    // Subtract subtitleSyncOffset: positive delay (+0.5s) shifts cues later in time
    const effectiveTime = (videoPlayer.currentTime || 0) + currentStreamOffset - (subtitleSyncOffset || 0);

    if (track.cues && track.cues.length > 0) {
      for (let i = 0; i < track.cues.length; i++) {
        const c = track.cues[i];
        if (effectiveTime >= c.startTime && effectiveTime <= c.endTime) {
          cuesToRender.push(c);
        }
      }
    } else if (track.activeCues && track.activeCues.length > 0 && subtitleSyncOffset === 0 && currentStreamOffset === 0) {
      cuesToRender = Array.from(track.activeCues);
    }

    if (cuesToRender.length > 0) {
      let fullText = '';
      for (let i = 0; i < cuesToRender.length; i++) {
        fullText += (cuesToRender[i].text || '') + '\n';
      }
      const safeHtml = fullText
        .trim()
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/&lt;(\/?[ibu])&gt;/gi, '<$1>'); // Restore safe styling tags (italic, bold, underline)

      // CRITICAL: Prevent pulsing/flicker by only mutating DOM when cue text actually changes!
      if (overlay.dataset.lastCueHtml === safeHtml) {
        return;
      }
      overlay.dataset.lastCueHtml = safeHtml;
      overlay.innerHTML = `<span class="video-subtitles-cue-text">${safeHtml}</span>`;
      overlay.classList.remove('hidden');
    } else {
      if (overlay.dataset.lastCueHtml) {
        overlay.dataset.lastCueHtml = '';
        overlay.innerHTML = '';
        overlay.classList.add('hidden');
      }
    }
  };

  track.oncuechange = renderCues;
  if (videoPlayer._subCueHandler) {
    videoPlayer.removeEventListener('timeupdate', videoPlayer._subCueHandler);
  }
  videoPlayer._subCueHandler = renderCues;
  videoPlayer.addEventListener('timeupdate', videoPlayer._subCueHandler);
}

// ---------------- Audio Tracks & Surround Sound Manager ----------------

let availableAudioTracks = [];
let currentAudioTrackIndex = 0;

async function openAudioModal() {
  if (!audioModal) return;
  if (subtitleModal) subtitleModal.classList.add('hidden');
  audioModal.classList.remove('hidden');
  playerModal.classList.remove('idle');

  const infoHash = activeResolvedStream?.info_hash || '';
  if (!infoHash) {
    audioTrackList.innerHTML = `
      <div class="sub-track-item active focusable" tabindex="0">
        <span class="sub-track-radio">●</span>
        <span class="sub-track-title">Direct Stream Audio (Stereo / 5.1 Universal AAC)</span>
      </div>
    `;
    return;
  }

  audioTrackList.innerHTML = '<div class="sub-empty-prompt">🔍 Analyzing audio streams from media file...</div>';
  try {
    const res = await fetch(`/api/media_tracks?hash=${encodeURIComponent(infoHash)}`);
    const data = await res.json();
    availableAudioTracks = data.tracks?.audio || [];
    renderAudioTracksList();
  } catch (err) {
    audioTrackList.innerHTML = `<div class="sub-empty-prompt error">Failed to load audio tracks: ${err.message}</div>`;
  }

  setTimeout(() => {
    const firstFocus = audioModal.querySelector('.focusable');
    if (firstFocus) firstFocus.focus();
  }, 100);
}

function closeAudioModal() {
  if (audioModal) audioModal.classList.add('hidden');
  resetInactivityTimer();
  if (playerAudioBtn) playerAudioBtn.focus();
}


function renderAudioTracksList() {
  if (!availableAudioTracks || availableAudioTracks.length === 0) {
    audioTrackList.innerHTML = `
      <div class="sub-track-item active focusable" tabindex="0">
        <div style="display:flex;align-items:center;gap:12px;">
          <span class="sub-track-radio">●</span>
          <div>
            <div style="font-weight:700;color:#fff;">Universal High-Bitrate AAC Audio (5.1 / Stereo)</div>
            <div style="font-size:12px;color:#94a3b8;">Format: 256k AAC &bull; Universal TV &amp; Browser Decoding</div>
          </div>
        </div>
        <span class="tag-badge" style="background:#10b981;color:#fff;">✓ Active</span>
      </div>
    `;
    return;
  }

  audioTrackList.innerHTML = availableAudioTracks.map(t => {
    const isActive = t.audio_index === currentAudioTrackIndex;
    return `
      <div class="sub-track-item ${isActive ? 'active' : ''} focusable" tabindex="0" data-audio-idx="${t.audio_index}">
        <div style="display:flex;align-items:center;gap:12px;">
          <span class="sub-track-radio">${isActive ? '●' : '○'}</span>
          <div>
            <div style="font-weight:700;color:#fff;">${escapeHtml(t.title)}</div>
            <div style="font-size:12px;color:#94a3b8;">Format: ${escapeHtml(t.codec.toUpperCase())} (${t.channels} Channels) &bull; Track #${t.audio_index + 1}</div>
          </div>
        </div>
        <span class="tag-badge" style="background:${isActive ? '#10b981' : 'rgba(255,255,255,0.08)'};color:#fff;">
          ${isActive ? '✓ Playing' : 'Select'}
        </span>
      </div>
    `;
  }).join('');

  audioTrackList.querySelectorAll('.sub-track-item').forEach(item => {
    item.addEventListener('click', () => {
      const idx = parseInt(item.dataset.audioIdx, 10);
      switchAudioTrack(idx);
    });
  });
}

function switchAudioTrack(audioIndex) {
  currentAudioTrackIndex = audioIndex;
  renderAudioTracksList();

  const selected = availableAudioTracks.find(t => t.audio_index === audioIndex);
  if (selected && audioModalActiveBadge) {
    audioModalActiveBadge.textContent = selected.title;
  }

  // Reload stream with ?audio=audioIndex preserving playback time
  const baseUrl = baseStreamUrl || (activeResolvedStream && activeResolvedStream.url ? activeResolvedStream.url.split('?')[0] : '');
  if (baseUrl) {
    const curTime = currentStreamOffset + (videoPlayer.currentTime || 0);
    const targetSec = Math.max(0, Math.floor(curTime));
    currentStreamOffset = targetSec;
    const newUrl = `${baseUrl}?audio=${audioIndex}&start=${targetSec}`;
    isBuffering = true;
    playerModal.classList.add('buffering');
    loadMediaStream(newUrl, targetSec);
    showToast(`🔊 Switched Audio: ${selected ? selected.title : 'Track ' + audioIndex}`);
    closeAudioModal();
  }
}

// ---------------- Subtitle Manager & Dynamic Cue Sync ----------------

async function openSubtitleModal() {
  if (!subtitleModal) return;
  if (audioModal) audioModal.classList.add('hidden');
  subtitleModal.classList.remove('hidden');
  playerModal.classList.remove('idle');

  const subQueryInput = document.getElementById('sub-search-query-input');
  if (subQueryInput) {
    const itemTitle = selectedItem?.name || activeResolvedStream?.streamItem?.raw_title || '';
    subQueryInput.value = itemTitle;
  }

  renderActiveSubtitlesList();

  const id = selectedItem ? selectedItem.id : (activeResolvedStream?.streamItem?.id || '');
  if (id && availableSubtitles.length === 0) {
    await fetchOnlineSubtitles(id);
  } else {
    filterAndRenderSubtitles();
  }

  setTimeout(() => {
    const firstFocus = subtitleModal.querySelector('.focusable');
    if (firstFocus) firstFocus.focus();
  }, 100);
}

function closeSubtitleModal() {
  subtitleModal.classList.add('hidden');
  resetInactivityTimer();
  if (playerSubBtn) playerSubBtn.focus();
}


async function fetchOnlineSubtitles(id, infoHash = '', byHash = false) {
  const subQueryInput = document.getElementById('sub-search-query-input');
  const customQuery = subQueryInput ? subQueryInput.value.trim() : '';
  const itemTitle = customQuery || selectedItem?.name || activeResolvedStream?.streamItem?.raw_title || '';
  const titleParam = itemTitle ? `&title=${encodeURIComponent(itemTitle)}` : '';
  const queryParam = customQuery ? `&query=${encodeURIComponent(customQuery)}` : '';
  const hashParam = infoHash ? `&hash=${encodeURIComponent(infoHash)}` : (activeResolvedStream?.info_hash ? `&hash=${encodeURIComponent(activeResolvedStream.info_hash)}` : '');
  const byHashParam = byHash ? '&by_hash=1' : '';
  const langParam = `&lang=${encodeURIComponent(subLangSelect?.value || 'all')}`;

  if (!id && !infoHash && !customQuery) {
    subSearchResults.innerHTML = '<div class="sub-empty-prompt">No media ID or query found to search subtitles.</div>';
    return;
  }
  subSearchResults.innerHTML = `<div class="sub-empty-prompt">🔍 Fetching subtitles from OpenSubtitles &amp; VLSub (${byHash ? 'MovieHash' : 'Title query'})...</div>`;
  try {
    const res = await fetch(`/api/subtitles/search?id=${encodeURIComponent(id || '')}&type=${currentType}${hashParam}${titleParam}${queryParam}${byHashParam}${langParam}`);
    const data = await res.json();
    availableSubtitles = data.subtitles || [];
    filterAndRenderSubtitles();

    // Auto-select top English subtitle by default (embedded 100% synced or best release match)
    if (!activeSubtitle && availableSubtitles.length > 0) {
      const embeddedSub = availableSubtitles.find(s => s.is_embedded);
      const engSub = embeddedSub || availableSubtitles.find(s => s.lang === 'eng' || s.lang === 'en' || (s.label && s.label.toLowerCase().includes('english')));
      if (engSub) {
        logClient('INFO', `Auto-selected default subtitle: ${engSub.label || engSub.lang}`);
        activateSubtitle(engSub);
      }
    }
  } catch (err) {
    subSearchResults.innerHTML = `<div class="sub-empty-prompt error">Failed to load subtitles: ${err.message}</div>`;
  }
}

function filterAndRenderSubtitles() {
  const selectedLang = subLangSelect.value;
  let filtered = availableSubtitles;
  if (selectedLang !== 'all') {
    filtered = availableSubtitles.filter(s => s.lang === selectedLang);
  }

  if (filtered.length === 0) {
    subSearchResults.innerHTML = `<div class="sub-empty-prompt">No ${selectedLang.toUpperCase()} subtitles found. Select "All Languages" to view other tracks.</div>`;
    return;
  }

  subSearchResults.innerHTML = filtered.map(s => {
    const langName = LANG_NAMES[s.lang] || s.lang_display || s.lang?.toUpperCase() || 'English';
    const releaseDetail = s.release_title || s.subtitleFileName || s.movieReleaseName || s.details || '';
    const badge = s.releaseFormat || (s.is_embedded ? 'EMBEDDED' : (s.is_hash_match ? '100% SYNC' : (s.lang ? s.lang.toUpperCase() : 'SRT')));
    const isCurrent = activeSubtitle && activeSubtitle.url === s.url;
    const labelTitle = s.label || `${langName} Subtitle`;
    return `
      <div class="sub-search-item ${isCurrent ? 'active' : ''} focusable" tabindex="0" data-url="${escapeHtml(s.url)}" data-lang="${escapeHtml(s.lang || 'en')}" data-label="${escapeHtml(labelTitle)}">
        <div class="sub-item-info">
          <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
            <span class="sub-lang-badge">${escapeHtml((s.lang || 'en').toUpperCase())}</span>
            <span class="sub-item-title" style="font-weight:700;color:#ffffff;font-size:14px;">${escapeHtml(langName)}</span>
            ${badge ? `<span class="tag-badge" style="background:#334155;color:#38bdf8;font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;">${escapeHtml(badge)}</span>` : ''}
            ${s.is_hash_match ? `<span class="tag-badge" style="background:#065f46;color:#6ee7b7;font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;">★ 100% Synced</span>` : ''}
          </div>
          ${releaseDetail ? `<div class="sub-item-release" style="font-size:12px;color:#94a3b8;margin-top:3px;word-break:break-all;">${escapeHtml(releaseDetail)}</div>` : ''}
        </div>
        <button class="btn btn-secondary btn-sm focusable" tabindex="0" style="margin-left:12px;flex-shrink:0;">${isCurrent ? '✓ Active' : '📥 Load'}</button>
      </div>
    `;
  }).join('');

  subSearchResults.querySelectorAll('.sub-search-item').forEach(item => {
    item.addEventListener('click', () => {
      const sub = {
        url: item.dataset.url,
        lang: item.dataset.lang,
        label: item.dataset.label
      };
      activateSubtitle(sub);
    });
  });
}

function activateSubtitle(sub) {
  Array.from(videoPlayer.querySelectorAll('track')).forEach(t => t.remove());
  const overlay = document.getElementById('video-subtitles-overlay');
  if (overlay) {
    overlay.innerHTML = '';
    overlay.classList.add('hidden');
  }

  if (!sub || !sub.url) {
    activeSubtitle = null;
    showToast('Subtitles disabled');
    renderActiveSubtitlesList();
    filterAndRenderSubtitles();
    return;
  }

  activeSubtitle = sub;
  const track = document.createElement('track');
  track.kind = 'subtitles';
  track.label = sub.label;
  track.srclang = sub.lang;
  track.src = sub.url.startsWith('/') ? sub.url : `/api/subtitle_proxy?url=${encodeURIComponent(sub.url)}`;
  track.default = true;

  videoPlayer.appendChild(track);
  track.addEventListener('load', () => {
    track.track.mode = 'hidden';
    if (subtitleSyncOffset !== 0) applySubtitleSyncOffset(subtitleSyncOffset, true);
    setupSubtitleTrackCueSync(track);
  });
  if (track.track) {
    track.track.mode = 'hidden';
    if (subtitleSyncOffset !== 0) applySubtitleSyncOffset(subtitleSyncOffset, true);
    setupSubtitleTrackCueSync(track);
  }

  showToast(`💬 Subtitles Active: ${sub.label}`);
  logClient('INFO', `Mounted subtitle: ${sub.label}`, { url: sub.url });
  renderActiveSubtitlesList();
  filterAndRenderSubtitles();
  setTimeout(() => closeSubtitleModal(), 600);
}

function renderActiveSubtitlesList() {
  subActiveList.innerHTML = `
    <div class="sub-track-item ${!activeSubtitle ? 'active' : ''} focusable" tabindex="0" data-action="off">
      <span class="sub-track-radio">${!activeSubtitle ? '●' : '○'}</span>
      <span class="sub-track-title">None / Subtitles Disabled</span>
    </div>
    ${activeSubtitle ? `
      <div class="sub-track-item active focusable" tabindex="0" data-action="active">
        <span class="sub-track-radio">●</span>
        <span class="sub-track-title">${escapeHtml(activeSubtitle.label)} (Active)</span>
      </div>
    ` : ''}
  `;

  subActiveList.querySelector('[data-action="off"]')?.addEventListener('click', () => {
    activateSubtitle(null);
  });
}

function showPlayerSpinner(title, sub) {
  if (!playerLoadingSpinner) return;
  playerLoadingSpinner.classList.remove('hidden', 'fade-out');
  if (tvLoadingText) tvLoadingText.textContent = title;
  if (tvLoadingSub) tvLoadingSub.textContent = sub;
}

function hidePlayerSpinner() {
  if (!playerLoadingSpinner) return;
  playerLoadingSpinner.classList.add('fade-out');
  setTimeout(() => {
    playerLoadingSpinner.classList.add('hidden');
  }, 350);
}

// ---------------- Catalog & Metadata ----------------

async function loadCatalog(type, genre = '', lang = '', year = '') {
  // Restore Continue Watching & Recommendations sections on home view
  renderContinueWatchingRow();
  renderRecommendationsRow();

  const activeYear = (year !== undefined && year !== null && year !== '') ? year : currentYear;

  const sectionTitle = document.getElementById('section-title');
  if (sectionTitle) {
    if (genre === 'SouthIndian' || genre === 'Tamil' || genre === 'Regional') {
      let langText = lang && lang !== 'all' ? `${lang.toUpperCase()} Cinema` : 'Regional Cinema';
      sectionTitle.textContent = activeYear ? `${langText} (${activeYear})` : langText;
    } else {
      let base = genre ? `${genre} Releases` : 'Latest & Top Rated Releases';
      sectionTitle.textContent = activeYear ? `${base} (${activeYear})` : base;
    }
  }
  const streamIndicator = document.getElementById('stream-count-indicator');
  if (streamIndicator) {
    streamIndicator.textContent = 'Sorted by Recent Releases & Rating ★';
  }

  const cacheKey = `sb_cat_${type}_${genre}_${lang}_${activeYear}`;
  let hadInstantCache = false;
  try {
    const cachedRaw = localStorage.getItem(cacheKey);
    if (cachedRaw) {
      const cachedData = JSON.parse(cachedRaw);
      if (Array.isArray(cachedData.items) && cachedData.items.length > 0) {
        currentItems = cachedData.items;
        renderMediaGrid(currentItems);
        if (currentItems.length > 0) {
          updateHeroBanner(currentItems[0]);
        }
        hadInstantCache = true;
      }
    }
  } catch (e) {}

  if (!hadInstantCache) {
    mediaGrid.innerHTML = '<div class="loading-spinner">Loading catalogue...</div>';
  }

  try {
    let url = `/api/catalog?type=${type}&genre=${encodeURIComponent(genre)}&skip=0`;
    if (lang && lang !== 'all') {
      url += `&lang=${encodeURIComponent(lang)}`;
    }
    if (activeYear) {
      url += `&year=${encodeURIComponent(activeYear)}`;
    }
    let res = null;
    let attempts = 0;
    while (attempts < 3) {
      try {
        res = await fetch(url);
        if (res && res.ok) break;
      } catch (e) {}
      attempts++;
      await new Promise(r => setTimeout(r, 600));
    }
    if (!res || !res.ok) {
      if (!hadInstantCache) {
        throw new Error(`Server returned HTTP ${res ? res.status : 'offline'}`);
      }
      return;
    }
    const data = await res.json();
    if (data && Array.isArray(data.items)) {
      currentItems = data.items;
      try {
        localStorage.setItem(cacheKey, JSON.stringify(data));
      } catch (e) {}
      renderMediaGrid(currentItems);

      if (currentItems.length > 0) {
        updateHeroBanner(currentItems[0]);
      }
    }
  } catch (err) {
    if (!hadInstantCache) {
      logClient('ERROR', `Failed to load catalogue: ${err.message}`);
      mediaGrid.innerHTML = `<div class="error-msg" style="text-align:center; padding: 40px;"><p>Failed to load catalogue: ${err.message}</p><button class="nav-btn focusable active" onclick="loadCatalog('${type}', '${genre}', '${lang}')" tabindex="0" style="margin-top:15px; padding: 10px 24px;">🔄 Retry</button></div>`;
      if (window.SpatialNav && typeof window.SpatialNav.refresh === 'function') {
        window.SpatialNav.refresh();
      }
    }
  }
}

async function executeSearch() {
  const query = searchInput.value.trim();
  if (!query) {
    loadCatalog(currentType, currentGenre);
    return;
  }

  // Explicitly hide Continue Watching and Recommended sections on Search Results
  const cwSec = document.getElementById('continue-watching-section');
  if (cwSec) cwSec.style.display = 'none';
  const recSec = document.getElementById('recommendations-section');
  if (recSec) recSec.style.display = 'none';

  const sectionTitle = document.getElementById('section-title');
  if (sectionTitle) {
    sectionTitle.textContent = `Search Results: "${query}"`;
  }
  const streamIndicator = document.getElementById('stream-count-indicator');
  if (streamIndicator) {
    streamIndicator.textContent = 'Filtered Titles';
  }

  logClient('INFO', `User search: "${query}"`);
  mediaGrid.innerHTML = '<div class="loading-spinner">Searching...</div>';
  try {
    const res = await fetch(`/api/search?q=${encodeURIComponent(query)}&type=${currentType}`);
    const data = await res.json();
    currentItems = data.items || [];
    renderMediaGrid(currentItems);

    if (currentItems.length > 0) {
      updateHeroBanner(currentItems[0]);
      // Automatically place cursor/focus on the first search result
      setTimeout(() => {
        const firstCard = mediaGrid.querySelector('.media-card');
        if (firstCard) {
          firstCard.focus();
          firstCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      }, 120);
    }
  } catch (err) {
    logClient('ERROR', `Search error for "${query}": ${err.message}`);
    mediaGrid.innerHTML = `<div class="error-msg">Search error: ${err.message}</div>`;
  }
}

// ---------------- Voice Search Bridge Handlers ----------------

window.onVoiceSearchStarted = function() {
  if (voiceOverlay) {
    voiceOverlay.classList.remove('hidden');
    if (voiceTitle) voiceTitle.textContent = 'Listening...';
    if (voiceSubtitle) voiceSubtitle.textContent = 'Speak a movie or TV show name';
  }
};

window.onVoiceAudioLevel = function(level) {
  if (voicePulseRing) {
    const scale = 1.0 + (level * 0.85);
    voicePulseRing.style.transform = `scale(${scale})`;
  }
  if (voiceWave) {
    const bars = voiceWave.querySelectorAll('.wave-bar');
    bars.forEach((b, i) => {
      const factor = (i % 2 === 0 ? 1 : 0.7);
      const h = Math.max(8, Math.round(level * 36 * factor));
      b.style.height = `${h}px`;
    });
  }
};

window.onVoiceSearchRecognizing = function() {
  if (voiceTitle) voiceTitle.textContent = 'Recognizing speech...';
  if (voiceSubtitle) voiceSubtitle.textContent = 'Processing speech with high accuracy...';
};

window.onVoiceSearchResult = function(query) {
  if (!query || !query.trim()) {
    window.onVoiceSearchError('No speech heard. Please try again.');
    return;
  }
  const cleanQuery = query.trim();
  if (voiceTitle) voiceTitle.textContent = `"${cleanQuery}"`;
  if (voiceSubtitle) voiceSubtitle.textContent = 'Searching catalogue...';
  
  if (searchInput) searchInput.value = cleanQuery;

  setTimeout(() => {
    if (voiceOverlay) voiceOverlay.classList.add('hidden');
    executeSearch();
  }, 350);
};

window.onVoiceSearchError = function(errorMsg) {
  if (voiceTitle) voiceTitle.textContent = 'Voice Search';
  if (voiceSubtitle) voiceSubtitle.textContent = errorMsg || 'Could not understand audio.';
  setTimeout(() => {
    if (voiceOverlay) voiceOverlay.classList.add('hidden');
  }, 2000);
};

function updateHeroBanner(item) {
  selectedItem = item;
  heroTitle.textContent = item.name || 'Untitled';
  heroRating.textContent = `★ ${item.imdbRating || 'N/A'}`;
  heroYear.textContent = item.releaseInfo || item.year || '';
  heroGenre.textContent = (item.genres || []).slice(0, 3).join(' • ');
  heroDesc.textContent = item.description || 'No synopsis available.';

  const backdrop = item.background || item.poster;
  if (backdrop) {
    heroBg.style.backgroundImage = `url('${backdrop}')`;
  }

  // Speculative Stream Prewarm: pre-query streams into server cache while browsing so "Watch Now" is instantaneous!
  if (item && item.id && item.type !== 'series') {
    clearTimeout(window._heroPrewarmTimer);
    window._heroPrewarmTimer = setTimeout(() => {
      fetch(`/api/streams?id=${item.id}&type=movie`).catch(() => {});
    }, 450);
  }
}

function renderMediaGrid(items) {
  if (!items || items.length === 0) {
    mediaGrid.innerHTML = '<div class="empty-msg">No titles found. Try another category.</div>';
    return;
  }

  mediaGrid.innerHTML = '';
  items.forEach((item) => {
    const card = document.createElement('div');
    card.className = 'media-card focusable';
    card.tabIndex = 0;
    card.dataset.id = item.id;

    card.innerHTML = `
      <div class="media-poster-wrapper">
        <img class="media-poster" src="${item.poster || ''}" alt="${item.name || ''}" referrerpolicy="no-referrer" loading="lazy" onerror="if (!this.dataset.triedFallback && '${item.id}'.startsWith('tt')) { this.dataset.triedFallback = '1'; this.src = 'https://images.metahub.space/poster/medium/${item.id}/img'; } else { this.onerror=null; this.src='data:image/svg+xml,%3Csvg xmlns=\\'http://www.w3.org/2000/svg\\' width=\\'200\\' height=\\'300\\' viewBox=\\'0 0 200 300\\'%3E%3Crect width=\\'200\\' height=\\'300\\' fill=\\'%231b2230\\'/%3E%3Ctext x=\\'50%25\\' y=\\'50%25\\' fill=\\'%2364748b\\' font-size=\\'14\\' text-anchor=\\'middle\\' dy=\\'.3em\\'%3ENo Poster%3C/text%3E%3C/svg%3E'; }">
        ${item.imdbRating ? `<span class="card-rating">★ ${item.imdbRating}</span>` : ''}
      </div>
      <div class="card-info">
        <div class="card-title">${item.name || 'Unknown'}</div>
        <div class="card-meta">
          <span>${item.releaseInfo || item.year || ''}</span>
          <span>${item.type === 'series' ? 'TV' : 'Movie'}</span>
        </div>
      </div>
    `;

    // Speculative card dwell pre-warming disabled on Fire TV Stick Lite to prevent memory pressure
    const startDwell = () => {};
    const cancelDwell = () => {};

    card.addEventListener('focus', () => {
      updateHeroBanner(item);
      startDwell();
    });
    card.addEventListener('blur', cancelDwell);
    card.addEventListener('mouseenter', startDwell);
    card.addEventListener('mouseleave', cancelDwell);

    card.addEventListener('click', () => {
      cancelDwell();
      openDetails(item, false);
    });

    // Attach Apple TV Action Sheet Context Menu (hold Enter / press Menu)
    attachCardContextMenu(card, () => item);

    mediaGrid.appendChild(card);
  });
}

// ---------------- Details & Streams ----------------

async function openDetails(item, shouldAutoPlay = false) {
  cancelAllHoldTimers();
  hideContextMenu();
  window.lastFocusedElement = document.activeElement;
  selectedItem = item;
  autoPlayRequested = shouldAutoPlay;
  currentStreams = [];
  currentTamilBlastersStreams = [];
  isFetchingStreams = true;

  // Reset source tab to Torrentio
  if (tabSourceTorrentio && tabSourceTamilblasters) {
    tabSourceTorrentio.classList.add('active');
    tabSourceTamilblasters.classList.remove('active');
    if (sectionTorrentioStreams) sectionTorrentioStreams.classList.remove('hidden');
    if (sectionTamilblastersStreams) sectionTamilblastersStreams.classList.add('hidden');
    activeSourceTab = 'torrentio';
  }

  logClient('INFO', `Opened details for "${item.name}" (autoPlay=${shouldAutoPlay})`);
  detailsModal.classList.remove('hidden');

  // Immediately set initial available data
  modalPoster.src = item.poster || '';
  modalPoster.onerror = () => {
    modalPoster.src = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='130' height='195' viewBox='0 0 130 195'%3E%3Crect width='130' height='195' fill='%231b2230'/%3E%3Ctext x='50%25' y='50%25' fill='%2364748b' font-size='14' text-anchor='middle' dy='.3em'%3ENo Poster%3C/text%3E%3C/svg%3E";
  };
  modalTitle.textContent = item.name || '';
  modalRating.textContent = `★ ${item.imdbRating || '...'}`;
  modalYear.textContent = item.releaseInfo || item.year || '';
  modalRuntime.textContent = item.runtime || 'Loading...';
  modalGenres.textContent = (item.genres || []).join(', ') || 'Loading genres...';
  modalSynopsis.textContent = item.description || 'Fetching synopsis & IMDb details...';
  modalCast.innerHTML = `<strong>Cast:</strong> ${(item.cast || []).slice(0, 5).join(', ') || 'Loading cast...'}`;

  const isSeries = item.type === 'series';
  const hist = getHistory();
  const existing = hist[item.id];
  const modalPlayStartBtn = document.getElementById('modal-play-start-btn');

  // Keep strictly "Play Now" and "Start Over" with no additional cluttered text
  if (modalPlayMain) {
    modalPlayMain.textContent = 'Play Now';
  }

  if (modalPlayStartBtn) {
    modalPlayStartBtn.classList.remove('hidden');
    modalPlayStartBtn.onclick = (e) => {
      e.stopPropagation();
      if (existing) {
        existing.position = 0;
        saveHistory(hist);
      }
      showToast('↺ Starting from beginning (00:00:00)...');
      launchStream(currentStreams[0] || selectedStream, true);
    };
  }

  // Play button is always ready
  modalPlayNowBtn.disabled = false;
  if (shouldAutoPlay) {
    modalPlayNowBtn.classList.add('loading');
  } else {
    modalPlayNowBtn.classList.remove('loading');
  }

  // Ensure streams accordion is collapsed by default
  streamsCollapsibleContent.classList.add('hidden');
  accordionArrow.classList.remove('rotated');
  accordionTitle.textContent = '▼ Choose Alternative Sources & Qualities (Click to Expand)';

  modalPlayNowBtn.focus();

  // Reset Series State
  currentSeriesVideos = [];
  currentSelectedSeason = 1;
  currentSelectedEpisode = 1;
  if (seriesSelector) {
    seriesSelector.classList.add('hidden');
    if (seriesSeasonsRow) seriesSeasonsRow.innerHTML = '';
    if (seriesEpisodesRow) seriesEpisodesRow.innerHTML = '';
  }

  // 1. Concurrent execution: Fetch full metadata in background without blocking stream playback!
  fetchMetadata(item.id, item.type || 'movie');

  // 2. Query stream sources for movies immediately without waiting for metadata
  if (!isSeries) {
    loadStreams(item.id, 'movie');
  }
}

async function fetchMetadata(imdbId, type = 'movie') {
  try {
    const res = await fetch(`/api/meta?id=${imdbId}&type=${type}`);
    const data = await res.json();
    const meta = data.meta;
    if (meta) {
      if (meta.poster && modalPoster) {
        modalPoster.src = meta.poster;
      }
      if (meta.description) {
        modalSynopsis.textContent = meta.description;
      }
      if (meta.imdbRating) modalRating.textContent = `★ ${meta.imdbRating}`;
      if (meta.runtime) {
        modalRuntime.textContent = typeof meta.runtime === 'number' ? `${meta.runtime} min` : meta.runtime;
      }
      if (meta.genres && meta.genres.length > 0) {
        modalGenres.textContent = meta.genres.join(' • ');
      }
      
      const detailsList = [];
      if (meta.cast && meta.cast.length > 0) {
        detailsList.push(`<strong>Cast:</strong> ${meta.cast.slice(0, 6).join(', ')}`);
      }
      if (meta.director && meta.director.length > 0) {
        const dStr = Array.isArray(meta.director) ? meta.director.join(', ') : meta.director;
        detailsList.push(`<strong>Director:</strong> ${dStr}`);
      }
      if (meta.writer && meta.writer.length > 0) {
        const wStr = Array.isArray(meta.writer) ? meta.writer.join(', ') : meta.writer;
        detailsList.push(`<strong>Writer:</strong> ${wStr}`);
      }
      if (detailsList.length > 0) {
        modalCast.innerHTML = detailsList.join(' &nbsp;•&nbsp; ');
      }
      if (meta.background) {
        heroBg.style.backgroundImage = `url('${meta.background}')`;
      }

      if (type === 'series' && meta.videos && meta.videos.length > 0) {
        currentSeriesVideos = meta.videos;
        setupSeriesEpisodeSelector();
      }
    }
  } catch (err) {
    logClient('WARN', `Metadata fetch error for ${imdbId}: ${err.message}`);
    if (modalSynopsis.textContent.includes('Fetching')) {
      modalSynopsis.textContent = 'Synopsis details currently unavailable.';
    }
  }
}

function setupSeriesEpisodeSelector() {
  if (!seriesSelector || !currentSeriesVideos || currentSeriesVideos.length === 0) return;

  seriesSelector.classList.remove('hidden');

  // Group into unique sorted seasons
  const seasonSet = new Set();
  currentSeriesVideos.forEach(v => {
    if (v.season !== undefined && v.season !== null) seasonSet.add(v.season);
  });
  const seasons = Array.from(seasonSet).sort((a, b) => a - b);
  currentSelectedSeason = seasons.length > 0 ? seasons[0] : 1;

  if (seriesSeasonsRow) {
    seriesSeasonsRow.innerHTML = seasons.map(s => `
      <button class="season-pill focusable ${s === currentSelectedSeason ? 'active' : ''}" tabindex="0" data-season="${s}">
        Season ${s}
      </button>
    `).join('');

    seriesSeasonsRow.querySelectorAll('.season-pill').forEach(btn => {
      btn.addEventListener('click', () => {
        const s = parseInt(btn.dataset.season, 10);
        currentSelectedSeason = s;
        seriesSeasonsRow.querySelectorAll('.season-pill').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        renderEpisodesForSeason(s);
      });
    });
  }

  renderEpisodesForSeason(currentSelectedSeason);
}

function renderEpisodesForSeason(season) {
  if (!seriesEpisodesRow || !currentSeriesVideos) return;

  const eps = currentSeriesVideos
    .filter(v => (v.season || 1) === season)
    .sort((a, b) => (a.episode || a.number || 0) - (b.episode || b.number || 0));

  if (eps.length === 0) {
    seriesEpisodesRow.innerHTML = '<div style="color:var(--text-muted);font-size:12px;padding:8px;">No episodes listed.</div>';
    return;
  }

  const firstEp = eps[0];
  currentSelectedEpisode = firstEp.episode || firstEp.number || 1;

  seriesEpisodesRow.innerHTML = eps.map(ep => {
    const epNum = ep.episode || ep.number || 1;
    const isActive = epNum === currentSelectedEpisode;
    const epName = ep.name || `Episode ${epNum}`;
    return `
      <div class="episode-pill focusable ${isActive ? 'active' : ''}" tabindex="0" data-season="${season}" data-episode="${epNum}">
        <div class="episode-pill-num">EPISODE ${epNum}</div>
        <div class="episode-pill-title" title="${escapeHtml(epName)}">${escapeHtml(epName)}</div>
      </div>
    `;
  }).join('');

  seriesEpisodesRow.querySelectorAll('.episode-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      const epNum = parseInt(pill.dataset.episode, 10);
      selectEpisode(season, epNum);
    });
  });

  selectEpisode(season, currentSelectedEpisode, false);
}

function selectEpisode(season, epNum, scroll = true) {
  currentSelectedSeason = season;
  currentSelectedEpisode = epNum;

  if (seriesEpisodesRow) {
    seriesEpisodesRow.querySelectorAll('.episode-pill').forEach(pill => {
      const pEp = parseInt(pill.dataset.episode, 10);
      if (pEp === epNum) {
        pill.classList.add('active');
        if (scroll) pill.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' });
      } else {
        pill.classList.remove('active');
      }
    });
  }

  const epObj = currentSeriesVideos.find(v => (v.season || 1) === season && ((v.episode || v.number) === epNum));
  const epName = epObj ? (epObj.name || `Episode ${epNum}`) : `Episode ${epNum}`;

  if (modalPlayMain) {
    modalPlayMain.textContent = 'Play Now';
  }
  modalPlaySub.textContent = `Finding healthiest swarm for S${season}:E${epNum}...`;

  loadStreams(selectedItem.id, 'series', season, epNum);
}

let prewarmedHash = null;
const prewarmedItemIds = new Set();

function speculativePrewarmCard(item) {
  if (!item || !item.id || prewarmedItemIds.has(item.id)) return;
  prewarmedItemIds.add(item.id);
  const type = item.type || 'movie';
  // Silently query streams for hovered/focused item and prime top swarm in background
  fetch(`/api/streams?id=${item.id}&type=${type}`)
    .then(r => r.json())
    .then(data => {
      if (data && data.streams && data.streams.length > 0) {
        const top = data.streams[0];
        if (!top.is_debrid_cached && top.info_hash) {
          logClient('INFO', `[NetflixFastStart] Speculative dwell hit on "${item.name}" - pre-buffering ${top.resolution}`);
          prewarmStream(top);
        }
      }
    })
    .catch(() => {});
}

function prewarmStream(streamItem) {
  if (!streamItem || !streamItem.info_hash || streamItem.info_hash === prewarmedHash) return;
  prewarmedHash = streamItem.info_hash;
  logClient('INFO', `[PreWarm] Priming swarm in background for ${streamItem.resolution} (${streamItem.info_hash.substring(0, 8)})`);
  fetch('/api/prewarm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      info_hash: streamItem.info_hash,
      raw_title: streamItem.raw_title || '',
      companion_hashes: streamItem.companion_hashes || [],
      companion_trackers: streamItem.companion_trackers || streamItem.trackers || []
    })
  }).then(r => r.json()).then(data => {
    logClient('INFO', `[PreWarm] Result for ${streamItem.info_hash.substring(0, 8)}: meta=${data.has_metadata}, piece0=${data.have_piece_0}`);
  }).catch(() => {});
}

async function loadStreams(imdbId, type = 'movie', season = null, episode = null) {
  if (!imdbId || imdbId === 'undefined' || imdbId === 'null') {
    if (streamsList) streamsList.innerHTML = '<div class="error-msg">Please select a title from the catalog.</div>';
    return;
  }
  isFetchingStreams = true;
  streamsList.innerHTML = '<div class="loading-streams">⚡ Scanning 1337x, YTS, TorrentGalaxy, TPB, and Debrid Cache...</div>';
  if (badgeCountTorrentio) badgeCountTorrentio.textContent = '...';

  // Kick off TamilBlasters concurrently in isolated fault domain (zero blocking)
  loadTamilBlastersStreams(imdbId, type, season, episode);

  try {
    let url = `/api/streams?id=${encodeURIComponent(imdbId)}&type=${encodeURIComponent(type)}`;
    if (type === 'series' && season !== null && episode !== null) {
      url += `&season=${season}&episode=${episode}`;
    }
    const res = await fetch(url);
    if (!res.ok) {
      throw new Error(`Server returned HTTP ${res.status}`);
    }
    const data = await res.json();
    currentStreams = data.streams || [];
    if (badgeCountTorrentio) badgeCountTorrentio.textContent = currentStreams.length;
    renderStreams(currentStreams);

    if (currentStreams.length > 0) {
      const top = currentStreams[0];
      const recReason = top.recommendation_reason || `${top.resolution} (${top.seeders} seeds)`;
      modalPlaySub.textContent = `Auto-Selected: ${recReason}`;
      accordionTitle.textContent = `▼ Choose Alternative Quality & Source (${currentStreams.length} Available)`;

      logClient('INFO', `Found ${currentStreams.length} streams. Top pick: ${top.resolution} (${top.seeders} seeds)`);

      // Speculative Pre-warming: Prime the BitTorrent swarm in background so piece 0 is ready BEFORE user clicks Play!
      if (!top.is_debrid_cached && top.info_hash) {
        prewarmStream(top);
      }

      if (autoPlayRequested) {
        autoPlayRequested = false;
        modalPlayNowBtn.classList.remove('loading');
        launchStream(top);
      }
    } else {
      modalPlaySub.textContent = 'No playable streams found for this title.';
      accordionTitle.textContent = '▼ No Streams Found';
      logClient('WARN', `No streams found for title id: ${imdbId}`);
    }
  } catch (err) {
    logClient('ERROR', `Failed to query streams: ${err.message}`);
    streamsList.innerHTML = `<div class="error-msg">Failed to query streams: ${err.message}</div>`;
    modalPlaySub.textContent = 'Stream query error.';
    if (badgeCountTorrentio) badgeCountTorrentio.textContent = '!';
  } finally {
    isFetchingStreams = false;
  }
}

async function loadTamilBlastersStreams(imdbId, type = 'movie', season = null, episode = null) {
  if (!tamilblastersStreamsList) return;
  isFetchingTamilBlasters = true;
  if (tamilblastersErrorBox) {
    tamilblastersErrorBox.classList.add('hidden');
    tamilblastersErrorBox.innerHTML = '';
  }
  tamilblastersStreamsList.innerHTML = '<div class="loading-streams">🇮🇳 Connecting to MediaFusion / TamilBlasters...</div>';
  if (badgeCountTamilblasters) badgeCountTamilblasters.textContent = '...';

  try {
    let url = `/api/streams/tamilblasters?id=${imdbId}&type=${type}`;
    if (type === 'series' && season !== null && episode !== null) {
      url += `&season=${season}&episode=${episode}`;
    }
    const res = await fetch(url);
    const data = await res.json();

    if (data.status === 'error') {
      logClient('WARN', `TamilBlasters error: ${data.message}`);
      if (badgeCountTamilblasters) badgeCountTamilblasters.textContent = '!';
      tamilblastersStreamsList.innerHTML = '';
      if (tamilblastersErrorBox) {
        tamilblastersErrorBox.classList.remove('hidden');
        tamilblastersErrorBox.innerHTML = `
          <div class="stream-error-icon">⚠️</div>
          <div class="stream-error-content">
            <div class="stream-error-title">TamilBlasters (MediaFusion) Unavailable</div>
            <div class="stream-error-desc">${data.message || 'MediaFusion service is unreachable or undergoing maintenance.'} Torrentio and core playback remain fully functional.</div>
            <button class="btn-retry-tb focusable" id="btn-retry-tb-action" tabindex="0">↻ Retry Connection</button>
          </div>
        `;
        const retryBtn = document.getElementById('btn-retry-tb-action');
        if (retryBtn) {
          retryBtn.addEventListener('click', () => loadTamilBlastersStreams(imdbId, type, season, episode));
        }
      }
      return;
    }

    currentTamilBlastersStreams = data.streams || [];
    if (badgeCountTamilblasters) badgeCountTamilblasters.textContent = currentTamilBlastersStreams.length;
    renderTamilBlastersStreams(currentTamilBlastersStreams);
    logClient('INFO', `Found ${currentTamilBlastersStreams.length} TamilBlasters streams via MediaFusion`);
  } catch (err) {
    logClient('ERROR', `TamilBlasters fetch failed: ${err.message}`);
    if (badgeCountTamilblasters) badgeCountTamilblasters.textContent = '!';
    tamilblastersStreamsList.innerHTML = '';
    if (tamilblastersErrorBox) {
      tamilblastersErrorBox.classList.remove('hidden');
      tamilblastersErrorBox.innerHTML = `
        <div class="stream-error-icon">⚠️</div>
        <div class="stream-error-content">
          <div class="stream-error-title">TamilBlasters Connection Error</div>
          <div class="stream-error-desc">${err.message}. Torrentio streams remain active.</div>
          <button class="btn-retry-tb focusable" id="btn-retry-tb-action" tabindex="0">↻ Retry Connection</button>
        </div>
      `;
      const retryBtn = document.getElementById('btn-retry-tb-action');
      if (retryBtn) {
        retryBtn.addEventListener('click', () => loadTamilBlastersStreams(imdbId, type, season, episode));
      }
    }
  } finally {
    isFetchingTamilBlasters = false;
  }
}

function renderTamilBlastersStreams(streams) {
  if (!tamilblastersStreamsList) return;
  if (!streams || streams.length === 0) {
    tamilblastersStreamsList.innerHTML = '<div class="empty-msg">No TamilBlasters releases found for this title. Try the Torrentio tab.</div>';
    return;
  }

  let filtered = streams;
  if (activeTbQualityFilter === '4k') {
    filtered = streams.filter(s => s.resolution && s.resolution.includes('4K'));
  } else if (activeTbQualityFilter === '1080p') {
    filtered = streams.filter(s => s.resolution && s.resolution.includes('1080p'));
  } else if (activeTbQualityFilter === '720p') {
    filtered = streams.filter(s => s.resolution && s.resolution.includes('720p'));
  }

  if (filtered.length === 0) {
    tamilblastersStreamsList.innerHTML = `<div class="empty-msg">No ${activeTbQualityFilter.toUpperCase()} releases found. Try 'All Formats'.</div>`;
    return;
  }

  tamilblastersStreamsList.innerHTML = '';
  filtered.forEach((s) => {
    const card = document.createElement('div');
    const isRec = s.auto_recommended;
    card.className = `stream-card focusable ${s.is_debrid_cached ? 'cached' : ''} ${isRec ? 'recommended' : ''}`;
    card.tabIndex = 0;

    const audioLangs = s.audio_tag || s.audio || 'Multi-Audio';

    card.innerHTML = `
      <div class="stream-left">
        <div class="stream-title">
          ${isRec ? `<span class="rec-badge">★ TOP PICK</span> ` : ''}
          <span class="tag-badge" style="background:#b91c1c;color:#fff;font-weight:700;">🇮🇳 TamilBlasters</span>
          ${(s.raw_title || '').split('\n')[0] || s.provider}
        </div>
        <div class="stream-tags">
          ${isRec && s.recommendation_reason ? `<span class="tag-badge" style="background:linear-gradient(135deg, #f97316, #ea580c);color:#fff;font-weight:700;">${s.recommendation_reason}</span>` : ''}
          ${s.is_debrid_cached ? '<span class="tag-badge tag-debrid">⚡ RD+ CACHED</span>' : ''}
          <span class="tag-badge tag-res">${s.resolution}</span>
          <span class="tag-badge tag-codec">${s.codec_tag || s.codec}</span>
          <span class="tag-badge" style="background:#1e293b;color:#38bdf8;border:1px solid #38bdf844;">🔊 ${audioLangs}</span>
          ${s.hdr ? '<span class="tag-badge" style="background:#b45309;color:#fef3c7;">HDR</span>' : ''}
          ${s.dolby_vision ? '<span class="tag-badge" style="background:#581c87;color:#f3e8ff;">DV</span>' : ''}
          ${s.seeders > 0 ? `<span class="tag-badge tag-seeders">👤 ${s.seeders} seeds</span>` : ''}
          ${s.size && s.size !== 'Unknown' ? `<span class="tag-badge tag-seeders">💾 ${s.size}</span>` : ''}
        </div>
      </div>
      <div class="stream-action">
        ${s.is_debrid_cached ? '▶ Play Instant' : '▶ Play Stream'}
      </div>
    `;

    card.addEventListener('click', () => launchStream(s));
    tamilblastersStreamsList.appendChild(card);
  });
}

let activeQualityFilter = 'all';

function setupFilterButtons() {
  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeQualityFilter = btn.dataset.filter;
      renderStreams(currentStreams);
    });
  });

  // TamilBlasters Quality Filters
  document.querySelectorAll('.tb-filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tb-filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeTbQualityFilter = btn.dataset.filter;
      renderTamilBlastersStreams(currentTamilBlastersStreams);
    });
  });

  // Source Provider Switcher (Torrentio vs TamilBlasters)
  if (tabSourceTorrentio && tabSourceTamilblasters) {
    tabSourceTorrentio.addEventListener('click', () => {
      tabSourceTorrentio.classList.add('active');
      tabSourceTamilblasters.classList.remove('active');
      if (sectionTorrentioStreams) sectionTorrentioStreams.classList.remove('hidden');
      if (sectionTamilblastersStreams) sectionTamilblastersStreams.classList.add('hidden');
      activeSourceTab = 'torrentio';
    });

    tabSourceTamilblasters.addEventListener('click', () => {
      tabSourceTamilblasters.classList.add('active');
      tabSourceTorrentio.classList.remove('active');
      if (sectionTamilblastersStreams) sectionTamilblastersStreams.classList.remove('hidden');
      if (sectionTorrentioStreams) sectionTorrentioStreams.classList.add('hidden');
      activeSourceTab = 'tamilblasters';
    });
  }

  // Accordion Toggle for alternative streams
  toggleStreamsBtn.addEventListener('click', () => {
    streamsCollapsibleContent.classList.toggle('hidden');
    accordionArrow.classList.toggle('rotated');
  });

  modalPlayNowBtn.addEventListener('click', () => {
    logClient('INFO', 'User clicked primary "Play Now" button');
    if (isFetchingStreams) {
      autoPlayRequested = true;
      modalPlayNowBtn.classList.add('loading');
      modalPlaySub.textContent = '⚡ Starting player the moment search completes...';
      showToast('Finding best 1080p stream...');
      return;
    }

    if (currentStreams && currentStreams.length > 0) {
      launchStream(currentStreams[0]);
    } else {
      showToast('Searching for available streams...');
      const isSeries = selectedItem && selectedItem.type === 'series';
      loadStreams(selectedItem.id, selectedItem.type || 'movie', isSeries ? currentSelectedSeason : null, isSeries ? currentSelectedEpisode : null);
    }
  });

  // Next / Previous Episode Buttons in Player
  if (btnNextEp) {
    btnNextEp.addEventListener('click', playNextEpisode);
  }
  if (btnPrevEp) {
    btnPrevEp.addEventListener('click', playPrevEpisode);
  }
}

function scheduleNextEpisodePrefetch(season, epNum) {
  preloadedNextStream = null;
  if (!selectedItem || selectedItem.type !== 'series') return;
  const nextEp = currentSeriesVideos.find(v => (v.season || 1) === season && ((v.episode || v.number) === epNum + 1));
  if (!nextEp) return;
  const nextSeason = nextEp.season || season;
  const nextNum = nextEp.episode || (epNum + 1);

  setTimeout(async () => {
    if (!currentPlayingEpisode || currentPlayingEpisode.season !== season || currentPlayingEpisode.episode !== epNum) return;
    try {
      logClient('INFO', `[PreFetch] Speculatively pre-fetching stream metadata for S${nextSeason}:E${nextNum}`);
      const res = await fetch(`/api/streams?id=${selectedItem.id}&type=series&season=${nextSeason}&episode=${nextNum}`);
      const data = await res.json();
      if (data.streams && data.streams.length > 0) {
        preloadedNextStream = {
          season: nextSeason,
          episode: nextNum,
          streamItem: data.streams[0]
        };
        logClient('INFO', `[PreFetch] Ready: S${nextSeason}:E${nextNum} (${data.streams[0].resolution})`);
        if (data.streams[0].info_hash && !data.streams[0].is_debrid_cached) {
          prewarmStream(data.streams[0]);
        }
      }
    } catch (e) {
      logClient('WARN', `PreFetch error: ${e.message}`);
    }
  }, 10000);
}

async function switchEpisodeInPlayer(season, epNum) {
  if (!selectedItem) return;
  const epObj = currentSeriesVideos.find(v => (v.season || 1) === season && ((v.episode || v.number) === epNum));
  const epName = epObj ? (epObj.name || `Episode ${epNum}`) : `Episode ${epNum}`;

  logClient('INFO', `[SeamlessSwitch] Transitioning directly to S${season}:E${epNum} inside player`);
  showToast(`⏭ Switching to S${season}:E${epNum}...`);
  showPlayerSpinner(`Switching to S${season}:E${epNum}...`, `Preparing ${epName}`);
  playerModal.classList.add('buffering');

  videoPlayer.pause();
  clearInterval(streamHudInterval);

  currentSelectedSeason = season;
  currentSelectedEpisode = epNum;

  try {
    let topStream = null;
    if (preloadedNextStream && preloadedNextStream.season === season && preloadedNextStream.episode === epNum && preloadedNextStream.streamItem) {
      logClient('INFO', `[SeamlessSwitch] Utilizing instant pre-fetched stream for S${season}:E${epNum}`);
      topStream = preloadedNextStream.streamItem;
      preloadedNextStream = null;
    } else {
      const res = await fetch(`/api/streams?id=${selectedItem.id}&type=series&season=${season}&episode=${epNum}`);
      const data = await res.json();
      const streams = data.streams || [];
      if (streams.length > 0) {
        topStream = streams[0];
      }
    }

    if (!topStream) {
      showToast(`No playable streams found for S${season}:E${epNum}`);
      hidePlayerSpinner();
      return;
    }

    const rRes = await fetch('/api/resolve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(topStream)
    });
    const rData = await rRes.json();
    const resolved = rData.resolved;
    if (!resolved || !resolved.url) {
      showToast('Could not resolve episode stream.');
      hidePlayerSpinner();
      return;
    }

    activeResolvedStream = { streamItem: topStream, resolved, info_hash: topStream.info_hash };
    baseStreamUrl = resolved.url.split('?')[0];
    currentStreamOffset = 0;
    hasAutoStarted = false;
    userExplicitlyPaused = false;
    isBuffering = true;

    playerTitle.textContent = `${selectedItem.name} — S${season}:E${epNum}`;
    playerSubtitle.textContent = `${epName} • ${topStream.resolution} • ${topStream.codec}`;

    const nextEp = currentSeriesVideos.find(v => (v.season || 1) === season && ((v.episode || v.number) === epNum + 1));
    currentPlayingEpisode = {
      seriesId: selectedItem.id,
      season: season,
      episode: epNum,
      nextEp: nextEp
    };

    if (btnNextEp) {
      if (nextEp) {
        btnNextEp.classList.remove('hidden');
        btnNextEp.title = `Next: S${season}:E${epNum + 1} (${nextEp.name || 'Episode ' + (epNum + 1)})`;
      } else {
        btnNextEp.classList.add('hidden');
      }
    }
    if (btnPrevEp) {
      if (epNum > 1) {
        btnPrevEp.classList.remove('hidden');
        btnPrevEp.title = `Previous: S${season}:E${epNum - 1}`;
      } else {
        btnPrevEp.classList.add('hidden');
      }
    }

    seekCurrentTime.textContent = '00:00:00';
    tvSeekProgress.style.width = '0%';
    tvSeekThumb.style.left = '0%';
    tvSeekBuffered.style.width = '0%';

    availableSubtitles = [];
    activeSubtitle = null;
    Array.from(videoPlayer.querySelectorAll('track')).forEach(t => t.remove());

    loadMediaStream(resolved.url, 0);

    if (resolved.status_url) {
      streamHudInterval = setInterval(() => pollStreamHUD(resolved.status_url), 1500);
      pollStreamHUD(resolved.status_url);
    }

    scheduleNextEpisodePrefetch(season, epNum);
    fetchOnlineSubtitles(selectedItem.id);

  } catch (err) {
    showToast(`Error switching episode: ${err.message}`);
    logClient('ERROR', `Episode switch error: ${err.message}`);
    hidePlayerSpinner();
  }
}

async function playNextEpisode() {
  if (!currentPlayingEpisode || !currentPlayingEpisode.nextEp) {
    showToast('No more episodes in this season.');
    return;
  }
  const nextNum = currentPlayingEpisode.nextEp.episode || currentPlayingEpisode.nextEp.number;
  const nextSeason = currentPlayingEpisode.nextEp.season || currentPlayingEpisode.season || currentSelectedSeason;
  switchEpisodeInPlayer(nextSeason, nextNum);
}

async function playPrevEpisode() {
  if (!currentPlayingEpisode || currentPlayingEpisode.episode <= 1) {
    showToast('Already at the first episode.');
    return;
  }
  const prevNum = currentPlayingEpisode.episode - 1;
  const season = currentPlayingEpisode.season || currentSelectedSeason;
  switchEpisodeInPlayer(season, prevNum);
}

function renderStreams(streams) {
  if (!streams || streams.length === 0) {
    streamsList.innerHTML = '<div class="empty-msg">No active streams found for this title.</div>';
    return;
  }

  let filtered = streams;
  if (activeQualityFilter === '4k') {
    filtered = streams.filter(s => s.resolution && s.resolution.includes('4K'));
  } else if (activeQualityFilter === '1080p') {
    filtered = streams.filter(s => s.resolution && s.resolution.includes('1080p'));
  } else if (activeQualityFilter === '720p') {
    filtered = streams.filter(s => s.resolution && s.resolution.includes('720p'));
  }

  if (filtered.length === 0) {
    streamsList.innerHTML = `<div class="empty-msg">No ${activeQualityFilter.toUpperCase()} streams found. Try 'All'.</div>`;
    return;
  }

  streamsList.innerHTML = '';
  filtered.forEach((s) => {
    const card = document.createElement('div');
    const isRec = s.auto_recommended;
    const isTierRec = s.tier_recommended;
    card.className = `stream-card focusable ${s.is_debrid_cached ? 'cached' : ''} ${isRec ? 'recommended' : ''}`;
    card.tabIndex = 0;

    const scoreVal = s.score || 0;
    const scoreText = scoreVal >= 900 ? `${scoreVal} pts • Optimal` : `${scoreVal} pts`;
    const scoreStyle = scoreVal >= 850 ? 'background:linear-gradient(135deg, #16a34a, #15803d);color:#fff;' : 'background:#334155;color:#e2e8f0;';

    card.innerHTML = `
      <div class="stream-left">
        <div class="stream-title">
          ${isRec ? `<span class="rec-badge">★ TOP PICK</span> ` : ''}
          ${isTierRec && s.tier_badge && !isRec ? `<span class="rec-badge" style="background:#0284c7;color:#fff;">${s.tier_badge}</span> ` : ''}
          ${(s.raw_title || '').split('\n')[0] || s.provider}
        </div>
        <div class="stream-tags">
          ${isRec && s.recommendation_reason ? `<span class="tag-badge" style="background:linear-gradient(135deg, #f97316, #ea580c);color:#fff;font-weight:700;">${s.recommendation_reason}</span>` : ''}
          ${s.faststart_badge ? `<span class="tag-badge" style="background:linear-gradient(135deg, #059669, #047857);color:#fff;font-weight:700;">${s.faststart_badge}</span>` : ''}
          ${scoreVal > 0 ? `<span class="tag-badge" style="${scoreStyle};font-weight:700;">🎯 ${scoreText}</span>` : ''}
          ${s.is_debrid_cached ? '<span class="tag-badge tag-debrid">⚡ RD+ CACHED</span>' : ''}
          <span class="tag-badge tag-res">${s.resolution}</span>
          <span class="tag-badge tag-codec">${s.codec_tag || s.codec}</span>
          ${s.audio_tag || s.audio ? `<span class="tag-badge" style="background:#1e293b;color:#38bdf8;border:1px solid #38bdf844;">🔊 ${s.audio_tag || s.audio}</span>` : ''}
          ${s.hdr ? '<span class="tag-badge" style="background:#b45309;color:#fef3c7;">HDR</span>' : ''}
          ${s.dolby_vision ? '<span class="tag-badge" style="background:#581c87;color:#f3e8ff;">DV</span>' : ''}
          ${s.cross_torrent_count > 1 ? `<span class="tag-badge" style="background:linear-gradient(135deg, #0d9488, #0f766e);color:#ccfbf1;font-weight:700;">⚡ Fused (${s.cross_torrent_count} Swarms)</span>` : ''}
          ${s.seeders > 0 ? `<span class="tag-badge tag-seeders">👤 ${s.seeders} seeds</span>` : ''}
          ${s.size && s.size !== 'Unknown' ? `<span class="tag-badge tag-seeders">💾 ${s.size}</span>` : ''}
        </div>
      </div>
      <div class="stream-action">
        ${s.is_debrid_cached ? '▶ Play Instant' : '▶ Play Stream'}
      </div>
    `;

    card.addEventListener('click', () => launchStream(s));
    streamsList.appendChild(card);
  });
}

// ---------------- In-App Stream Launch & Playback ----------------

window.onNativePlaybackStopped = function(posSec, durSec) {
  logClient('INFO', `Native Media3 playback stopped at ${posSec}s / ${durSec}s`);
  if (selectedItem && durSec > 0) {
    updateWatchHistory(selectedItem, currentSelectedSeason, currentSelectedEpisode, posSec, durSec);
    renderContinueWatchingRow();
  }
};

async function launchStream(streamItem, forceStartFromBeginning = false) {
  if (!streamItem) return;

  try {
    modalPlayNowBtn.classList.add('loading');
    modalPlaySub.textContent = '⚡ Starting player...';
    showToast(`Launching ${streamItem.resolution}...`);
    logClient('INFO', `Starting stream: ${streamItem.resolution}`, { hash: streamItem.info_hash, seeds: streamItem.seeders });

    const res = await fetch('/api/resolve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(streamItem)
    });
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(`Resolve failed (HTTP ${res.status}): ${errText}`);
    }
    const data = await res.json();
    const resolved = data.resolved;

    modalPlayNowBtn.classList.remove('loading');

    if (!resolved) {
      showToast('Could not resolve stream.');
      logClient('ERROR', 'Stream resolution returned empty');
      return;
    }

    // Bridge to Native Media3 ExoPlayer (Pure GPU Zero-Copy Hardware Acceleration) if running inside Bravia TV app
    if (window.BraviaNative && typeof window.BraviaNative.launchNativePlayer === 'function') {
      const rawStreamUrl = resolved.raw_url || resolved.url;
      let title = selectedItem ? selectedItem.name : (streamItem.raw_title || 'StreamBravia 4K');
      if (selectedItem && selectedItem.type === 'series') {
        title += ` — S${currentSelectedSeason}:E${currentSelectedEpisode}`;
      }
      const hist = getHistory();
      const existing = (selectedItem && hist[selectedItem.id]) ? hist[selectedItem.id] : null;
      let startPosMs = 0;
      if (!forceStartFromBeginning && existing && existing.position && existing.duration && existing.position < existing.duration * 0.95) {
        if (selectedItem.type !== 'series' || (existing.season === currentSelectedSeason && existing.episode === currentSelectedEpisode)) {
          startPosMs = Math.floor(existing.position * 1000);
        }
      }
      logClient('INFO', `Handing off stream to Native Media3 SurfaceView Player: ${rawStreamUrl}`);
      showToast('Launching Hardware GPU Player (Direct Tunneling)...');
      if (detailsModal) detailsModal.classList.add('hidden');
      const infoHash = streamItem.info_hash || '';
      const imdbId = selectedItem ? selectedItem.id : '';
      const mediaType = currentType || 'movie';
      const season = currentSelectedSeason || 0;
      const episode = currentSelectedEpisode || 0;

      const origLang = (selectedItem && selectedItem.original_language) ? selectedItem.original_language : (currentTamilBlastersStreams && currentTamilBlastersStreams.length > 0 ? 'Tamil' : '');

      if (typeof window.BraviaNative.launchNativePlayerEx === 'function') {
        window.BraviaNative.launchNativePlayerEx(rawStreamUrl, title, JSON.stringify(availableSubtitles || []), startPosMs, infoHash, imdbId, mediaType, season, episode, origLang);
      } else {
        window.BraviaNative.launchNativePlayer(rawStreamUrl, title, JSON.stringify(availableSubtitles || []), startPosMs);
      }
      return;
    }

    activeResolvedStream = { streamItem, resolved, info_hash: streamItem.info_hash };
    baseStreamUrl = resolved.url.split('?')[0];
    currentStreamOffset = 0;
    hasAutoStarted = false;
    userExplicitlyPaused = false;
    isBuffering = true;
    logClient('INFO', `Stream resolved: ${resolved.url}`);

    // Parse and fix media runtime for rock-solid seek bar
    currentTotalDuration = parseRuntimeSeconds(selectedItem ? selectedItem.runtime : null);

    // Hide Details Modal, Open Fullscreen In-App TV Player
    detailsModal.classList.add('hidden');
    playerModal.classList.remove('hidden');
    playerModal.classList.remove('idle', 'seeking');
    playerModal.classList.add('buffering');

    // Auto-enter fullscreen mode for TV & desktop
    enterFullscreen(playerModal);

    if (selectedItem && selectedItem.type === 'series') {
      const epObj = currentSeriesVideos.find(v => (v.season || 1) === currentSelectedSeason && ((v.episode || v.number) === currentSelectedEpisode));
      const epName = epObj ? (epObj.name || `Episode ${currentSelectedEpisode}`) : `Episode ${currentSelectedEpisode}`;
      playerTitle.textContent = `${selectedItem.name} — S${currentSelectedSeason}:E${currentSelectedEpisode}`;
      playerSubtitle.textContent = `${epName} • ${streamItem.resolution} • ${streamItem.codec}`;

      const nextEp = currentSeriesVideos.find(v => (v.season || 1) === currentSelectedSeason && ((v.episode || v.number) === currentSelectedEpisode + 1));
      currentPlayingEpisode = {
        seriesId: selectedItem.id,
        season: currentSelectedSeason,
        episode: currentSelectedEpisode,
        nextEp: nextEp
      };
      if (btnNextEp) {
        if (nextEp) {
          btnNextEp.classList.remove('hidden');
          btnNextEp.title = `Next: S${currentSelectedSeason}:E${currentSelectedEpisode + 1} (${nextEp.name || 'Episode ' + (currentSelectedEpisode + 1)})`;
        } else {
          btnNextEp.classList.add('hidden');
        }
      }
      if (btnPrevEp) {
        if (currentSelectedEpisode > 1) {
          btnPrevEp.classList.remove('hidden');
          btnPrevEp.title = `Previous: S${currentSelectedSeason}:E${currentSelectedEpisode - 1}`;
        } else {
          btnPrevEp.classList.add('hidden');
        }
      }
      // Start speculative next episode pre-fetch in background
      scheduleNextEpisodePrefetch(currentSelectedSeason, currentSelectedEpisode);
    } else {
      currentPlayingEpisode = null;
      if (btnNextEp) btnNextEp.classList.add('hidden');
      if (btnPrevEp) btnPrevEp.classList.add('hidden');
      playerTitle.textContent = selectedItem ? selectedItem.name : 'StreamBravia';
      playerSubtitle.textContent = `${streamItem.resolution} • ${streamItem.codec} • ${streamItem.audio || 'Stereo'}`;
    }
    // Start history tracking
    startHistoryTracking();
    if (playerAudioBtn) playerAudioBtn.title = `Audio Tracks: ${streamItem.audio || 'Dolby Atmos / 5.1'}`;
    if (audioModalActiveBadge) audioModalActiveBadge.textContent = streamItem.audio || 'Universal AAC Active';
    updatePlayPauseUI(false);

    // Reset seek bar values
    seekCurrentTime.textContent = '00:00:00';
    seekTotalTime.textContent = formatTime(currentTotalDuration);
    tvSeekProgress.style.width = '0%';
    tvSeekThumb.style.left = '0%';
    tvSeekBuffered.style.width = '0%';

    // Reset subtitles
    availableSubtitles = [];
    activeSubtitle = null;
    Array.from(videoPlayer.querySelectorAll('track')).forEach(t => t.remove());

    // Show TV Buffering Spinner immediately while stream establishes
    showPlayerSpinner(`Connecting to ${streamItem.seeders || 'Swarm'} Seeders...`, 'Pre-buffering 4K/1080p stream with lookahead for smooth playback');

    // Reset HUD
    hudSpeed.textContent = 'Connecting...';
    hudPeers.textContent = `${streamItem.seeders || 0} swarm seeds`;
    hudBuffer.textContent = 'Buffering 0%';
    hudFile.textContent = (streamItem.raw_title || '').split('\n')[0];

    // Load stream URL directly into HTML5 Video Element
    let startPosSec = 0;
    const webHist = getHistory();
    const webExisting = (selectedItem && webHist[selectedItem.id]) ? webHist[selectedItem.id] : null;
    if (!forceStartFromBeginning && webExisting && webExisting.position && webExisting.position > 60 && webExisting.position < (webExisting.duration || 7200) * 0.95) {
      if (selectedItem.type !== 'series' || (webExisting.season === currentSelectedSeason && webExisting.episode === currentSelectedEpisode)) {
        startPosSec = Math.floor(webExisting.position);
      }
    }

    loadMediaStream(resolved.url, startPosSec);

    // Start 5-second inactivity auto-hide timer
    resetInactivityTimer();

    // Start polling stream health HUD
    clearInterval(streamHudInterval);
    if (resolved.status_url) {
      streamHudInterval = setInterval(() => pollStreamHUD(resolved.status_url), 1500);
      pollStreamHUD(resolved.status_url);
    }

    // Pre-fetch subtitles in background for instant availability (pass info_hash for embedded sync)
    if (selectedItem && selectedItem.id) {
      fetchOnlineSubtitles(selectedItem.id, streamItem.info_hash);
    }

    if (btnPlayPause) {
      btnPlayPause.focus();
    } else {
      videoPlayer.focus();
    }
  } catch (err) {
    modalPlayNowBtn.classList.remove('loading');
    showToast(`Playback error: ${err.message}`);
    logClient('ERROR', `Playback error: ${err.message}`);
  }
}

async function pollStreamHUD(statusUrl) {
  try {
    const res = await fetch(statusUrl);
    const data = await res.json();

    if (data.status === 'ok') {
      const speedRatio = data.speed_ratio ? ` (${data.speed_ratio}× Target)` : '';
      hudSpeed.textContent = `${data.download_speed}${speedRatio}`;

      const fusedStr = data.fused_companions > 0 ? ` • ⚡ ${data.fused_companions + 1} Swarms` : '';
      hudPeers.textContent = `${data.peers} peers (${data.seeds} seeds${fusedStr})`;

      const stateTag = data.buffer_state ? ` [${data.buffer_state.toUpperCase()}]` : '';
      const bufSec = data.buffer_seconds !== undefined ? `${data.buffer_seconds}s` : `${data.buffer_percent}%`;
      hudBuffer.textContent = `${bufSec}${stateTag}`;
      if (data.file_name) hudFile.textContent = data.file_name;

      // Auto-trigger video play once startup buffer is verified
      if (data.ready_to_play && !userExplicitlyPaused) {
        if (!hasAutoStarted || videoPlayer.error || (videoPlayer.paused && (videoPlayer.currentTime || 0) < 0.2)) {
          hasAutoStarted = true;
          if (videoPlayer.error || !videoPlayer.src || videoPlayer.networkState === HTMLMediaElement.NETWORK_NO_SOURCE) {
            videoPlayer.src = baseStreamUrl;
          }
          videoPlayer.play().then(() => {
            hidePlayerSpinner();
            resetInactivityTimer(true);
          }).catch(e => console.log('Autoplay handled:', e));
        }
      }
    }
  } catch (e) {
    // Silent fail on polling
  }
}

function closePlayer() {
  logClient('INFO', 'Closing in-app video player');
  if (hlsInstance) {
    try {
      hlsInstance.destroy();
    } catch (e) {}
    hlsInstance = null;
  }
  clearInterval(streamHudInterval);
  clearTimeout(inactivityTimer);
  clearTimeout(seekCommitTimeout);

  if (document.fullscreenElement) {
    if (document.exitFullscreen) document.exitFullscreen().catch(() => {});
  }

  videoPlayer.pause();
  videoPlayer.removeAttribute('src');
  videoPlayer.load();
  Array.from(videoPlayer.querySelectorAll('track')).forEach(t => t.remove());
  const overlay = document.getElementById('video-subtitles-overlay');
  if (overlay) {
    overlay.innerHTML = '';
    overlay.classList.add('hidden');
  }

  isBuffering = false;
  currentStreamOffset = 0;
  baseStreamUrl = '';

  // Save progress before closing
  if (selectedItem) {
    const finalPos = currentStreamOffset + (videoPlayer.currentTime || 0);
    if (finalPos > 20) {
      updateWatchHistory(
        selectedItem,
        currentPlayingEpisode ? currentPlayingEpisode.season : null,
        currentPlayingEpisode ? currentPlayingEpisode.episode : null,
        finalPos, currentTotalDuration
      );
    }
  }
  stopHistoryTracking();
  renderContinueWatchingRow();
  renderRecommendationsRow();

  hidePlayerSpinner();
  playerModal.classList.add('hidden');
  playerModal.classList.remove('idle', 'seeking', 'buffering');
  subtitleModal.classList.add('hidden');
  if (audioModal) audioModal.classList.add('hidden');
}

function showToast(msg) {
  const toastEl = document.getElementById('toast');
  if (!toastEl) return;
  toastEl.textContent = msg;
  toastEl.classList.remove('hidden');
  clearTimeout(window._toastTimeout);
  window._toastTimeout = setTimeout(() => {
    toastEl.classList.add('hidden');
  }, 3200);
}

// ---------------- Live Diagnostics Logs Modal ----------------

function openLogsModal() {
  logsModal.classList.remove('hidden');
  fetchAndRenderLogs();
  clearInterval(logsInterval);
  logsInterval = setInterval(fetchAndRenderLogs, 2000);
  logClient('INFO', 'User opened live diagnostics log modal');
}

function closeLogsModal() {
  clearInterval(logsInterval);
  logsModal.classList.add('hidden');
}

async function fetchAndRenderLogs() {
  try {
    const res = await fetch('/api/logs?count=100');
    const data = await res.json();
    const logs = data.logs || [];

    if (logs.length === 0) {
      logsTerminal.innerHTML = '<div class="log-line">No recent logs recorded yet.</div>';
      return;
    }

    logsTerminal.innerHTML = logs.map(l => {
      const lvl = (l.level || 'INFO').toLowerCase();
      const tag = (l.tag || '').toLowerCase();
      let colorClass = 'info';
      if (lvl.includes('err')) colorClass = 'error';
      else if (lvl.includes('warn')) colorClass = 'warn';
      else if (tag.includes('stream') || tag.includes('torrent')) colorClass = 'stream';
      else if (tag.includes('server')) colorClass = 'server';

      return `<div class="log-line ${colorClass}">${escapeHtml(l.formatted)}</div>`;
    }).join('');

    logsTerminal.scrollTop = logsTerminal.scrollHeight;
  } catch (e) {
    logsTerminal.innerHTML = `<div class="log-line error">Failed to fetch logs: ${e.message}</div>`;
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ---------------- Settings & Content Moderation ----------------

async function loadSettings() {
  try {
    const res = await fetch('/api/settings');
    const data = await res.json();
    if (data.real_debrid_configured) {
      settingsStatus.innerHTML = `<span style="color:#10b981;">✓ Real-Debrid Active (${data.real_debrid_masked})</span>`;
    } else {
      settingsStatus.innerHTML = '<span style="color:#f59e0b;">⚠ No Debrid Token (Local BitTorrent Streamer Active)</span>';
    }

    if (filterAdultCheck) filterAdultCheck.checked = data.filter_adult !== false;
    if (filterParodiesCheck) filterParodiesCheck.checked = data.filter_parodies !== false;

    // Populate OTA version & repo info
    const appVersion = (window.BraviaNative && typeof window.BraviaNative.getAppVersion === 'function') ? window.BraviaNative.getAppVersion() : '1.0.0';
    const repoName = (window.BraviaNative && typeof window.BraviaNative.getGitHubRepo === 'function') ? window.BraviaNative.getGitHubRepo() : 'renopraveen/StreamBravia';
    if (settingsAppVersion) settingsAppVersion.textContent = 'v' + appVersion;
    if (settingsRepoName) settingsRepoName.textContent = repoName;
  } catch (e) {
    logClient('WARN', `Error loading settings: ${e.message}`);
  }
}

async function saveSettings() {
  const token = rdTokenInput.value.trim();
  const filterAdult = filterAdultCheck ? filterAdultCheck.checked : true;
  const filterParodies = filterParodiesCheck ? filterParodiesCheck.checked : true;

  try {
    const res = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        real_debrid_token: token,
        filter_adult: filterAdult,
        filter_parodies: filterParodies
      })
    });
    const data = await res.json();
    settingsStatus.innerHTML = `<span style="color:#10b981;">✓ ${data.message}</span>`;
    showToast('Settings saved successfully!');
    logClient('INFO', 'User updated and saved settings');
    setTimeout(() => {
      settingsModal.classList.add('hidden');
      loadSettings();
      if (searchInput.value.trim()) {
        executeSearch();
      } else {
        loadCatalog(currentType, currentGenre);
      }
    }, 800);
  } catch (e) {
    showToast('Error saving settings: ' + e.message);
  }
}

// ---------------- D-Pad Spatial Navigation for TV Remote ----------------

function isElementVisible(el) {
  if (!el || !(el instanceof Element)) return false;
  if (el.classList.contains('hidden')) return false;
  if (el.closest('.hidden')) return false;
  const s = window.getComputedStyle(el);
  if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
  const rect = el.getBoundingClientRect();
  return rect.width > 1 && rect.height > 1;
}

function getActiveModal() {
  const modals = [
    playerModal,
    voiceOverlay,
    contextMenuModal,
    updateModal,
    audioModal,
    subtitleModal,
    logsModal,
    detailsModal,
    settingsModal,
    genreMenu
  ];
  for (const m of modals) {
    if (m && !m.classList.contains('hidden') && window.getComputedStyle(m).display !== 'none') {
      return m;
    }
  }
  return null;
}

function getTopNavItems() {
  const items = Array.from(document.querySelectorAll(
    '.navbar .focusable, .navbar button:not([tabindex="-1"]):not([disabled]), .navbar input:not([tabindex="-1"]):not([disabled])'
  ));
  return items.filter((el, i, arr) => arr.indexOf(el) === i && isElementVisible(el));
}

function restoreFocusAfterModal() {
  if (window.lastFocusedElement && isElementVisible(window.lastFocusedElement)) {
    try {
      window.lastFocusedElement.focus();
      window.lastFocusedElement.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
      return;
    } catch (ignored) {}
  }
  const fallback = document.querySelector('#continue-watching-row .cw-card, #recommendations-row .rec-card, #media-grid .media-card, #hero-play-btn, .navbar .focusable');
  if (fallback && isElementVisible(fallback)) {
    try {
      fallback.focus();
      fallback.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
    } catch (ignored) {}
  }
}

function closeDetailsModal() {
  if (detailsModal) {
    detailsModal.classList.add('hidden');
  }
  restoreFocusAfterModal();
}

function openSettingsModal() {
  window.lastFocusedElement = document.activeElement;
  if (settingsModal) {
    settingsModal.classList.remove('hidden');
    setTimeout(() => {
      const firstFocus = settingsModal.querySelector('.focusable, input, button:not([disabled])');
      if (firstFocus) firstFocus.focus();
    }, 40);
  }
}

function closeSettingsModal() {
  if (settingsModal) {
    settingsModal.classList.add('hidden');
  }
  restoreFocusAfterModal();
}

window.triggerContextMenuOnFocused = function() {
  const cur = document.activeElement;
  if (!cur) return;
  const id = cur.dataset.id || cur.dataset.historyId;
  if (id) {
    const item = (currentItems && currentItems.find(i => i.id === id)) ||
                 (getHistory()[id] ? { ...getHistory()[id] } : { id: id });
    showContextMenu(item, cur);
  }
};

window.triggerSearchQuery = function(query) {
  if (!query) return;
  if (searchInput) searchInput.value = query;
  executeSearch();
};

window.triggerOpenItemById = async function(id) {
  if (!id) return;
  try {
    const res = await fetch(`/api/meta?id=${encodeURIComponent(id)}&type=movie`);
    const data = await res.json();
    if (data && data.meta) {
      openDetails(data.meta, false);
      return;
    }
  } catch (e) {}
  try {
    const res = await fetch(`/api/meta?id=${encodeURIComponent(id)}&type=series`);
    const data = await res.json();
    if (data && data.meta) {
      openDetails(data.meta, false);
    }
  } catch (e) {}
};

function findClosestByX(current, candidates) {
  if (!candidates || candidates.length === 0) return null;
  const curRect = current.getBoundingClientRect();
  const curCenterX = curRect.left + curRect.width / 2;
  let best = candidates[0];
  let minDiff = Infinity;
  for (const c of candidates) {
    const r = c.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const diff = Math.abs(cx - curCenterX);
    if (diff < minDiff) {
      minDiff = diff;
      best = c;
    }
  }
  return best;
}

function jumpToNextMainRow(direction, current) {
  const cwCards = Array.from(document.querySelectorAll('#continue-watching-row .cw-card')).filter(isElementVisible);
  if (cwCards.length > 0) {
    const target = current ? findClosestByX(current, cwCards) : cwCards[0];
    target.focus();
    target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    return;
  }
  const recCards = Array.from(document.querySelectorAll('#recommendations-row .rec-card')).filter(isElementVisible);
  if (recCards.length > 0) {
    const target = current ? findClosestByX(current, recCards) : recCards[0];
    target.focus();
    target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    return;
  }
  const gridCards = Array.from(document.querySelectorAll('#media-grid .media-card')).filter(isElementVisible);
  if (gridCards.length > 0) {
    const target = current ? findClosestByX(current, gridCards.slice(0, 8)) : gridCards[0];
    target.focus();
    target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  }
}

function jumpToMediaGrid(current) {
  const gridCards = Array.from(document.querySelectorAll('#media-grid .media-card')).filter(isElementVisible);
  if (gridCards.length === 0) return;
  const target = current ? findClosestByX(current, gridCards.slice(0, 8)) : gridCards[0];
  target.focus();
  target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
}

function findCardInNextRow(current, allCards, dir) {
  const curRect = current.getBoundingClientRect();
  const curCenterX = curRect.left + curRect.width / 2;
  let best = null;
  let minDiff = Infinity;

  for (const c of allCards) {
    if (c === current) continue;
    const r = c.getBoundingClientRect();
    const candCenterX = r.left + r.width / 2;

    if (dir === 'down') {
      if (r.top >= curRect.bottom - 10) {
        const vDist = r.top - curRect.bottom;
        const hDist = Math.abs(candCenterX - curCenterX);
        const score = vDist * 2 + hDist;
        if (score < minDiff) {
          minDiff = score;
          best = c;
        }
      }
    } else if (dir === 'up') {
      if (r.bottom <= curRect.top + 10) {
        const vDist = curRect.top - r.bottom;
        const hDist = Math.abs(candCenterX - curCenterX);
        const score = vDist * 2 + hDist;
        if (score < minDiff) {
          minDiff = score;
          best = c;
        }
      }
    }
  }
  return best;
}

function setupDpadNavigation() {
  window.addEventListener('keydown', (e) => {
    const activeModal = getActiveModal();

    const isBack = (
      e.key === 'Escape' ||
      e.key === 'Esc' ||
      e.key === 'Back' ||
      e.keyCode === 27 ||
      e.keyCode === 4 ||
      e.keyCode === 10009
    );

    // Handle Back button for active modals
    if (isBack) {
      if (activeModal === updateModal) {
        e.preventDefault();
        hideUpdateModal();
        return;
      }
      if (activeModal === contextMenuModal) {
        e.preventDefault();
        hideContextMenu();
        return;
      }
      if (activeModal === audioModal) {
        e.preventDefault();
        closeAudioModal();
        return;
      }
      if (activeModal === subtitleModal) {
        e.preventDefault();
        closeSubtitleModal();
        return;
      }
      if (activeModal === voiceOverlay) {
        e.preventDefault();
        voiceOverlay.classList.add('hidden');
        if (window.BraviaNative && typeof window.BraviaNative.cancelVoiceSearch === 'function') {
          window.BraviaNative.cancelVoiceSearch();
        }
        restoreFocusAfterModal();
        return;
      }
      if (activeModal === logsModal) {
        e.preventDefault();
        closeLogsModal();
        return;
      }
      if (activeModal === detailsModal) {
        e.preventDefault();
        closeDetailsModal();
        return;
      }
      if (activeModal === settingsModal) {
        e.preventDefault();
        closeSettingsModal();
        return;
      }
      if (activeModal === genreMenu) {
        e.preventDefault();
        genreMenu.classList.add('hidden');
        restoreFocusAfterModal();
        return;
      }
      if (activeModal === playerModal) {
        e.preventDefault();
        closePlayer();
        return;
      }
    }

    // Video Player Controls D-Pad Handling
    if (activeModal === playerModal) {
      if (playerModal.classList.contains('idle')) {
        e.preventDefault();
        playerModal.classList.remove('idle');
        if (btnPlayPause) btnPlayPause.focus();
        resetInactivityTimer();
        return;
      }

      const cur = document.activeElement;
      const isButton = cur && cur.classList.contains('tv-btn');
      const isSeekBar = cur && (cur === tvSeekBarWrapper || cur.id === 'tv-seek-bar-wrapper');

      if (e.key === 'ArrowUp') {
        if (isButton) {
          e.preventDefault();
          if (tvSeekBarWrapper) tvSeekBarWrapper.focus();
          resetInactivityTimer();
          return;
        }
      }

      if (e.key === 'ArrowDown') {
        if (isSeekBar || playerModal.classList.contains('idle')) {
          e.preventDefault();
          playerModal.classList.remove('idle');
          if (btnPlayPause) btnPlayPause.focus();
          resetInactivityTimer();
          return;
        }
      }

      const btnRestart = document.getElementById('btn-restart');
      const actionButtons = [btnHudToggle, btnRestart, btnRewind, btnPrevEp, btnPlayPause, btnForward, btnNextEp, playerSubBtn, playerAudioBtn].filter(b => b && !b.classList.contains('hidden'));
      const btnIdx = actionButtons.indexOf(cur);

      if (btnIdx !== -1 && e.key === 'ArrowDown') {
        e.preventDefault();
        resetInactivityTimer();
        return;
      }

      if (btnIdx === -1 && !isSeekBar) {
        if (['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft'].includes(e.key)) {
          e.preventDefault();
          if (btnPlayPause) btnPlayPause.focus();
          resetInactivityTimer();
          return;
        }
      }

      if (btnIdx !== -1 && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
        e.preventDefault();
        resetInactivityTimer();
        if (e.key === 'ArrowRight' && btnIdx < actionButtons.length - 1) {
          actionButtons[btnIdx + 1].focus();
        } else if (e.key === 'ArrowLeft' && btnIdx > 0) {
          actionButtons[btnIdx - 1].focus();
        }
        return;
      }

      if (e.key === 'ArrowRight' || e.key === 'MediaFastForward' || e.key === 'FastForward' || e.keyCode === 228) {
        e.preventDefault();
        handleDpadSeek(1);
        return;
      }
      if (e.key === 'ArrowLeft' || e.key === 'MediaRewind' || e.key === 'Rewind' || e.keyCode === 227) {
        e.preventDefault();
        handleDpadSeek(-1);
        return;
      }

      const isSelectKey = (
        e.key === ' ' || e.key === 'Enter' || e.key === 'Select' ||
        e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 66
      );

      if (isSelectKey && isButton && cur && cur !== btnPlayPause) {
        e.preventDefault();
        cur.click();
        return;
      }

      const isPlayPause = (
        isSelectKey || e.key === 'MediaPlayPause' || e.key === 'MediaPlay' ||
        e.key === 'MediaPause' || e.key === 'Play' || e.key === 'Pause' || e.keyCode === 179
      );

      if (isPlayPause) {
        e.preventDefault();
        togglePlayPause();
        return;
      }
      return;
    }

    // Select / Enter Key Handling (D-Pad Center on Android TV remotes)
    const isSelectKey = (
      e.key === ' ' || e.key === 'Enter' || e.key === 'Select' ||
      e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 66
    );

    if (isSelectKey && (!activeModal || activeModal !== playerModal)) {
      const cur = document.activeElement;
      if (cur) {
        if (cur === searchInput) {
          e.preventDefault();
          executeSearch();
          return;
        }
        if (
          cur.tagName === 'BUTTON' ||
          cur.classList.contains('nav-btn') ||
          cur.classList.contains('genre-item') ||
          cur.classList.contains('icon-btn') ||
          cur.id === 'search-btn' ||
          cur.id === 'settings-btn' ||
          cur.id === 'hero-play-btn' ||
          cur.id === 'hero-details-btn' ||
          cur.id === 'modal-close-btn' ||
          cur.id === 'modal-play-now-btn' ||
          cur.id === 'modal-play-start-btn' ||
          cur.id === 'toggle-streams-btn' ||
          cur.classList.contains('source-tab')
        ) {
          e.preventDefault();
          cur.click();
          return;
        }
      }
    }

    // Directional Arrow Keys Navigation
    let dir = null;
    if (e.key === 'ArrowDown' || e.key === 'Down' || e.keyCode === 40) dir = 'down';
    else if (e.key === 'ArrowUp' || e.key === 'Up' || e.keyCode === 38) dir = 'up';
    else if (e.key === 'ArrowLeft' || e.key === 'Left' || e.keyCode === 37) dir = 'left';
    else if (e.key === 'ArrowRight' || e.key === 'Right' || e.keyCode === 39) dir = 'right';

    if (dir) {
      e.preventDefault();
      e.stopPropagation();

      let current = document.activeElement;

      // Focus recovery: if focus is lost, body, or inside a closed modal, recover immediately
      const isCurrentValid = current && isElementVisible(current) && current !== document.body &&
        (!activeModal || activeModal.contains(current)) &&
        (activeModal || !current.closest('.modal:not(.hidden)'));

      if (!isCurrentValid) {
        if (activeModal) {
          const focusables = Array.from(activeModal.querySelectorAll(
            '.focusable:not([tabindex="-1"]), button:not([disabled]):not([tabindex="-1"]), input:not([disabled]):not([tabindex="-1"])'
          )).filter(isElementVisible);
          if (focusables.length > 0) {
            focusables[0].focus();
            return;
          }
        } else {
          restoreFocusAfterModal();
          return;
        }
      }

      // CASE 1: Inside an Active Modal (Focus strictly trapped inside)
      if (activeModal) {
        // Specialized deterministic navigation for Details Modal
        if (activeModal === detailsModal) {
          const closeBtn = document.getElementById('modal-close-btn');
          const playBtn = document.getElementById('modal-play-now-btn');
          const startBtn = document.getElementById('modal-play-start-btn');
          const accordionBtn = document.getElementById('toggle-streams-btn');
          const tabTorr = document.getElementById('tab-source-torrentio');
          const tabTb = document.getElementById('tab-source-tamilblasters');
          const streamListEl = activeSourceTab === 'tamilblasters' ? document.getElementById('tamilblasters-streams-list') : document.getElementById('streams-list');
          const streamCards = Array.from(streamListEl?.querySelectorAll('.stream-card, .stream-item') || []).filter(isElementVisible);

          if (current === closeBtn) {
            if (dir === 'down' || dir === 'left') {
              if (playBtn) playBtn.focus();
              return;
            }
          } else if (current === playBtn) {
            if (dir === 'right') {
              if (startBtn && isElementVisible(startBtn)) startBtn.focus();
              return;
            } else if (dir === 'up') {
              if (closeBtn && isElementVisible(closeBtn)) closeBtn.focus();
              return;
            } else if (dir === 'down') {
              if (accordionBtn && isElementVisible(accordionBtn)) {
                accordionBtn.focus();
                accordionBtn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              }
              return;
            }
          } else if (current === startBtn) {
            if (dir === 'left') {
              if (playBtn) playBtn.focus();
              return;
            } else if (dir === 'up') {
              if (closeBtn && isElementVisible(closeBtn)) closeBtn.focus();
              return;
            } else if (dir === 'down') {
              if (accordionBtn && isElementVisible(accordionBtn)) {
                accordionBtn.focus();
                accordionBtn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              }
              return;
            }
          } else if (current === accordionBtn) {
            if (dir === 'up') {
              if (playBtn) playBtn.focus();
              return;
            } else if (dir === 'down') {
              if (tabTorr && isElementVisible(tabTorr)) {
                tabTorr.focus();
                tabTorr.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              } else if (streamCards.length > 0) {
                streamCards[0].focus();
                streamCards[0].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              }
              return;
            }
          } else if (current === tabTorr) {
            if (dir === 'right') {
              if (tabTb && isElementVisible(tabTb)) tabTb.focus();
              return;
            } else if (dir === 'up') {
              if (accordionBtn) accordionBtn.focus();
              return;
            } else if (dir === 'down') {
              if (streamCards.length > 0) {
                streamCards[0].focus();
                streamCards[0].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              }
              return;
            }
          } else if (current === tabTb) {
            if (dir === 'left') {
              if (tabTorr && isElementVisible(tabTorr)) tabTorr.focus();
              return;
            } else if (dir === 'up') {
              if (accordionBtn) accordionBtn.focus();
              return;
            } else if (dir === 'down') {
              if (streamCards.length > 0) {
                streamCards[0].focus();
                streamCards[0].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              }
              return;
            }
          } else {
            const scIdx = streamCards.indexOf(current);
            if (scIdx !== -1) {
              if (dir === 'down' && scIdx < streamCards.length - 1) {
                streamCards[scIdx + 1].focus();
                streamCards[scIdx + 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
                return;
              } else if (dir === 'up') {
                if (scIdx > 0) {
                  streamCards[scIdx - 1].focus();
                  streamCards[scIdx - 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
                } else {
                  if (tabTorr && isElementVisible(tabTorr)) tabTorr.focus();
                  else if (accordionBtn) accordionBtn.focus();
                }
                return;
              }
            }
          }
        }

        // Generic 2D spatial search inside other modals (settings, subs, audio, context menu, logs)
        const modalFocusables = Array.from(activeModal.querySelectorAll(
          '.focusable:not([tabindex="-1"]), button:not([disabled]):not([tabindex="-1"]), input:not([disabled]):not([tabindex="-1"])'
        )).filter(isElementVisible);

        if (modalFocusables.length === 0) return;
        const curIdx = modalFocusables.indexOf(current);

        if (curIdx === -1) {
          modalFocusables[0].focus();
          return;
        }

        const currentRect = current.getBoundingClientRect();
        const currentCenterX = currentRect.left + currentRect.width / 2;
        const currentCenterY = currentRect.top + currentRect.height / 2;
        let bestCandidate = null;
        let minDistance = Infinity;

        for (let i = 0; i < modalFocusables.length; i++) {
          const candidate = modalFocusables[i];
          if (candidate === current) continue;
          const candidateRect = candidate.getBoundingClientRect();
          const candCenterX = candidateRect.left + candidateRect.width / 2;
          const candCenterY = candidateRect.top + candidateRect.height / 2;

          let isValidDirection = false;
          let primaryDist = 0;
          let secondaryDist = 0;

          if (dir === 'right') {
            isValidDirection = candCenterX > currentCenterX;
            primaryDist = candidateRect.left - currentRect.right;
            secondaryDist = Math.abs(candCenterY - currentCenterY);
          } else if (dir === 'left') {
            isValidDirection = candCenterX < currentCenterX;
            primaryDist = currentRect.left - candidateRect.right;
            secondaryDist = Math.abs(candCenterY - currentCenterY);
          } else if (dir === 'down') {
            isValidDirection = candCenterY > currentCenterY;
            primaryDist = candidateRect.top - currentRect.bottom;
            secondaryDist = Math.abs(candCenterX - currentCenterX);
          } else if (dir === 'up') {
            isValidDirection = candCenterY < currentCenterY;
            primaryDist = currentRect.top - candidateRect.bottom;
            secondaryDist = Math.abs(candCenterX - currentCenterX);
          }

          if (isValidDirection) {
            const dist = Math.max(0, primaryDist) + secondaryDist * 2.2;
            if (dist < minDistance) {
              minDistance = dist;
              bestCandidate = candidate;
            }
          }
        }

        if (bestCandidate) {
          bestCandidate.focus();
          bestCandidate.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
        } else {
          if (dir === 'down' && curIdx < modalFocusables.length - 1) {
            modalFocusables[curIdx + 1].focus();
            modalFocusables[curIdx + 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          } else if (dir === 'up' && curIdx > 0) {
            modalFocusables[curIdx - 1].focus();
            modalFocusables[curIdx - 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          }
        }
        return;
      }

      // CASE 2: Main Home Screen (Predictable Section-Aware TV Navigation)
      // Section A: Top Navigation Bar
      const isTopNav = current && (current.closest('.nav-links') || current.closest('.nav-actions') || current.closest('.navbar'));
      if (isTopNav) {
        const topNavItems = getTopNavItems();
        const idx = topNavItems.indexOf(current);

        if (dir === 'right') {
          if (idx !== -1 && idx < topNavItems.length - 1) {
            topNavItems[idx + 1].focus();
          }
        } else if (dir === 'left') {
          if (idx > 0) {
            topNavItems[idx - 1].focus();
          }
        } else if (dir === 'down') {
          const heroPlay = document.getElementById('hero-play-btn');
          const heroDetails = document.getElementById('hero-details-btn');
          const heroVisible = heroPlay && isElementVisible(heroPlay);
          if (heroVisible) {
            const heroTargets = [heroPlay, heroDetails].filter(isElementVisible);
            const target = findClosestByX(current, heroTargets) || heroPlay;
            target.focus();
          } else {
            jumpToNextMainRow('down', current);
          }
        }
        return;
      }

      // Section B: Hero Actions
      const isHero = current && (current.id === 'hero-play-btn' || current.id === 'hero-details-btn' || current.closest('#hero-banner'));
      if (isHero) {
        const heroPlay = document.getElementById('hero-play-btn');
        const heroDetails = document.getElementById('hero-details-btn');

        if (dir === 'up') {
          const topNavItems = getTopNavItems();
          const target = findClosestByX(current, topNavItems) || document.querySelector('.nav-btn.active') || topNavItems[0];
          if (target) target.focus();
        } else if (dir === 'right') {
          if (current === heroPlay && heroDetails && isElementVisible(heroDetails)) heroDetails.focus();
        } else if (dir === 'left') {
          if (current === heroDetails && heroPlay && isElementVisible(heroPlay)) heroPlay.focus();
        } else if (dir === 'down') {
          jumpToNextMainRow('down', current);
        }
        return;
      }

      // Section C: Continue Watching Row
      const isCW = current && (current.classList.contains('cw-card') || current.closest('#continue-watching-row'));
      if (isCW) {
        const cwCards = Array.from(document.querySelectorAll('#continue-watching-row .cw-card')).filter(isElementVisible);
        const idx = cwCards.indexOf(current);

        if (dir === 'right') {
          if (idx !== -1 && idx < cwCards.length - 1) {
            cwCards[idx + 1].focus();
            cwCards[idx + 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
          }
        } else if (dir === 'left') {
          if (idx > 0) {
            cwCards[idx - 1].focus();
            cwCards[idx - 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
          }
        } else if (dir === 'up') {
          const heroPlay = document.getElementById('hero-play-btn');
          const heroDetails = document.getElementById('hero-details-btn');
          const heroVisible = heroPlay && isElementVisible(heroPlay);
          if (heroVisible) {
            const heroTargets = [heroPlay, heroDetails].filter(isElementVisible);
            const target = findClosestByX(current, heroTargets) || heroPlay;
            target.focus();
          } else {
            const topNavItems = getTopNavItems();
            const target = findClosestByX(current, topNavItems) || document.querySelector('.nav-btn.active') || topNavItems[0];
            if (target) target.focus();
          }
        } else if (dir === 'down') {
          const recCards = Array.from(document.querySelectorAll('#recommendations-row .rec-card')).filter(isElementVisible);
          if (recCards.length > 0) {
            const target = findClosestByX(current, recCards);
            target.focus();
            target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
          } else {
            jumpToMediaGrid(current);
          }
        }
        return;
      }

      // Section D: Recommendations Row
      const isRec = current && (current.classList.contains('rec-card') || current.closest('#recommendations-row'));
      if (isRec) {
        const recCards = Array.from(document.querySelectorAll('#recommendations-row .rec-card')).filter(isElementVisible);
        const idx = recCards.indexOf(current);

        if (dir === 'right') {
          if (idx !== -1 && idx < recCards.length - 1) {
            recCards[idx + 1].focus();
            recCards[idx + 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
          }
        } else if (dir === 'left') {
          if (idx > 0) {
            recCards[idx - 1].focus();
            recCards[idx - 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
          }
        } else if (dir === 'up') {
          const cwCards = Array.from(document.querySelectorAll('#continue-watching-row .cw-card')).filter(isElementVisible);
          if (cwCards.length > 0) {
            const target = findClosestByX(current, cwCards);
            target.focus();
            target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
          } else {
            const heroPlay = document.getElementById('hero-play-btn');
            const heroDetails = document.getElementById('hero-details-btn');
            const heroVisible = heroPlay && isElementVisible(heroPlay);
            if (heroVisible) {
              const heroTargets = [heroPlay, heroDetails].filter(isElementVisible);
              const target = findClosestByX(current, heroTargets) || heroPlay;
              target.focus();
            } else {
              const topNavItems = getTopNavItems();
              const target = findClosestByX(current, topNavItems) || document.querySelector('.nav-btn.active') || topNavItems[0];
              if (target) target.focus();
            }
          }
        } else if (dir === 'down') {
          jumpToMediaGrid(current);
        }
        return;
      }

      // Section E: Catalog Grid
      const isGrid = current && (current.classList.contains('media-card') || current.closest('#media-grid'));
      if (isGrid) {
        const gridCards = Array.from(document.querySelectorAll('#media-grid .media-card')).filter(isElementVisible);
        const idx = gridCards.indexOf(current);

        if (dir === 'right') {
          if (idx !== -1 && idx < gridCards.length - 1) {
            gridCards[idx + 1].focus();
            gridCards[idx + 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          }
        } else if (dir === 'left') {
          if (idx > 0) {
            gridCards[idx - 1].focus();
            gridCards[idx - 1].scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          }
        } else if (dir === 'down') {
          const nextRowCard = findCardInNextRow(current, gridCards, 'down');
          if (nextRowCard) {
            nextRowCard.focus();
            nextRowCard.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          }
        } else if (dir === 'up') {
          const prevRowCard = findCardInNextRow(current, gridCards, 'up');
          if (prevRowCard) {
            prevRowCard.focus();
            prevRowCard.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          } else {
            // Top row of media grid reached - navigate up
            const recCards = Array.from(document.querySelectorAll('#recommendations-row .rec-card')).filter(isElementVisible);
            if (recCards.length > 0) {
              const target = findClosestByX(current, recCards);
              target.focus();
              target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
            } else {
              const cwCards = Array.from(document.querySelectorAll('#continue-watching-row .cw-card')).filter(isElementVisible);
              if (cwCards.length > 0) {
                const target = findClosestByX(current, cwCards);
                target.focus();
                target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
              } else {
                const heroPlay = document.getElementById('hero-play-btn');
                const heroDetails = document.getElementById('hero-details-btn');
                const heroVisible = heroPlay && isElementVisible(heroPlay);
                if (heroVisible) {
                  const heroTargets = [heroPlay, heroDetails].filter(isElementVisible);
                  const target = findClosestByX(current, heroTargets) || heroPlay;
                  target.focus();
                } else {
                  const topNavItems = getTopNavItems();
                  const target = findClosestByX(current, topNavItems) || document.querySelector('.nav-btn.active') || topNavItems[0];
                  if (target) target.focus();
                }
              }
            }
          }
        }
        return;
      }

      // Default fallback
      const heroBtn = document.getElementById('hero-play-btn');
      if (heroBtn && isElementVisible(heroBtn)) {
        heroBtn.focus();
      } else {
        const firstCard = document.querySelector('.media-card');
        if (firstCard && isElementVisible(firstCard)) {
          firstCard.focus();
        } else {
          const activeNavBtn = document.querySelector('.nav-btn.active') || document.querySelector('.navbar .focusable');
          if (activeNavBtn) activeNavBtn.focus();
        }
      }
    }
  });
}
