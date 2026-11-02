// MangaBox fork: reader for reflowable EPUBs.
//
// Komga only serves page images for EPUBs that are image-only ("Divina compatible").
// For normal text EPUBs the page list is empty, so the image reader stays black.
// These books are opened here with epub.js instead, from the book file (or from the
// Komga WebPub resources when the user has no download right), and the reading position
// is synced with the Komga progression API (the same one Komga's own EPUB reader uses).
//
// Uses globals from index.html: mb, t, buildRequest, buildRequestMedia, callAPI,
// offlineSession, obIsBookCompleted, obLoadOfflineBook, showModal, closeModal, goBack,
// and the optional Capacitor helpers (isSystemBars, isVolumeButtons, isKeepAwake).

const epub = {
	active: false,
	bookId: null,
	book: null,
	rendition: null,
	ui: null,
	rtl: false,
	location: null,
	syncTimer: null,
	openToken: 0,
	touchX: 0,
	touchY: 0,
	touchT: 0,
};

const EPUB_THEMES = {
	light: { body: { color: '#000000 !important', background: '#ffffff !important' }, 'a': { color: '#000000 !important' } },
	sepia: { body: { color: '#3b2f1e !important', background: '#f4ecd8 !important' }, 'a': { color: '#3b2f1e !important' } },
	dark: { body: { color: '#d8d8d8 !important', background: '#111111 !important' }, 'a': { color: '#d8d8d8 !important' } },
};
const EPUB_THEME_ORDER = ['light', 'sepia', 'dark'];

// Book data -> true when it needs this reader instead of the image reader
function epubIsReflowable(bookData) {
	const media = bookData?.media;
	if (!media) return false;
	const isEpub = media.mediaProfile == 'EPUB' || media.mediaType == 'application/epub+zip';
	return isEpub && !media.epubDivinaCompatible;
}

function epubReaderActive() {
	return epub.active;
}

function epubPref(key, fallback) {
	try {
		return localStorage.getItem(`mb00Epub${key}`) ?? fallback;
	} catch {
		return fallback;
	}
}

function epubSetPref(key, value) {
	try {
		localStorage.setItem(`mb00Epub${key}`, value);
	} catch { }
}

function epubDeviceId() {
	let id = epubPref('DeviceId', null);
	if (!id) {
		id = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()).toString();
		epubSetPref('DeviceId', id);
	}
	return id;
}

function epubPosKey(bookId) {
	return `mbEpubPos_${mb.currentUserId}_${bookId}`;
}

function epubPendingKey() {
	return `mbEpubPending_${mb.currentUserId}`;
}

//MBX EPUB file loading

async function epubFetch(path, accept = '*/*') {
	const req = await buildRequestMedia('GET', null);
	req.headers = { ...req.headers, Accept: accept };
	return fetch(`${mb.baseUrl}${path}`, req);
}

// Returns the .epub as an ArrayBuffer, from the book file or rebuilt from the WebPub resources
async function epubFetchArrayBuffer(bookId, onProgress = null) {
	const res = await epubFetch(`/api/v1/books/${bookId}/file`);
	if (res.ok) return await res.arrayBuffer();
	// 403: the user has no FILE_DOWNLOAD role, the resources are still readable one by one
	if (res.status != 401 && res.status != 403) throw new Error(`Book file failed: ${res.status}`);
	return await epubBuildFromManifest(bookId, onProgress);
}

function epubEncodePath(path) {
	return path.split('/').map(encodeURIComponent).join('/');
}

function epubXmlEscape(text) {
	return String(text ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

async function epubBuildFromManifest(bookId, onProgress) {
	const res = await epubFetch(`/api/v1/books/${bookId}/manifest/epub`, 'application/webpub+json, application/json');
	if (!res.ok) throw new Error(`Book manifest failed: ${res.status}`);
	const manifest = await res.json();

	const resourcePath = (href) => {
		const index = href.indexOf('/resource/');
		return index < 0 ? null : decodeURIComponent(href.substring(index + '/resource/'.length).split('#')[0]);
	};
	const spine = (manifest.readingOrder || []).map(link => ({ path: resourcePath(link.href), type: link.type })).filter(item => item.path);
	const assets = (manifest.resources || []).map(link => ({ path: resourcePath(link.href), type: link.type })).filter(item => item.path);
	const files = [...spine, ...assets];

	const zip = new JSZip();
	zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
	zip.file('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="mangabox-package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');

	let done = 0;
	for (const file of files) {
		const fileRes = await epubFetch(`/api/v1/books/${bookId}/resource/${epubEncodePath(file.path)}`);
		if (fileRes.ok) zip.file(file.path, await fileRes.arrayBuffer());
		done++;
		onProgress?.(done / files.length);
	}

	// Table of contents rebuilt from the manifest, the original nav document is not identifiable
	const tocItems = (items) => '<ol>' + items.map(item => {
		const path = resourcePath(item.href);
		if (!path) return '';
		const anchor = item.href.includes('#') ? '#' + item.href.split('#')[1] : '';
		return `<li><a href="${epubXmlEscape(epubEncodePath(path) + anchor)}">${epubXmlEscape(item.title || path)}</a>${item.children?.length ? tocItems(item.children) : ''}</li>`;
	}).join('') + '</ol>';
	zip.file('mangabox-nav.xhtml', `<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>toc</title></head><body><nav epub:type="toc">${tocItems(manifest.toc || [])}</nav></body></html>`);

	const items = files.map((file, index) => `<item id="r${index}" href="${epubXmlEscape(epubEncodePath(file.path))}" media-type="${epubXmlEscape(file.type || 'application/octet-stream')}"/>`).join('');
	const itemrefs = spine.map((file, index) => `<itemref idref="r${index}"/>`).join('');
	const metadata = manifest.metadata || {};
	const layout = metadata.rendition?.layout == 'fixed' ? '<meta property="rendition:layout">pre-paginated</meta>' : '';
	const direction = metadata.readingProgression == 'rtl' ? ' page-progression-direction="rtl"' : '';
	zip.file('mangabox-package.opf', `<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">${epubXmlEscape(bookId)}</dc:identifier><dc:title>${epubXmlEscape(metadata.title || bookId)}</dc:title><dc:language>${epubXmlEscape(metadata.language || 'en')}</dc:language>${layout}</metadata><manifest><item id="mangabox-nav" href="mangabox-nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${items}</manifest><spine${direction}>${itemrefs}</spine></package>`);

	return await zip.generateAsync({ type: 'arraybuffer' });
}

// Offline copy: book.epub next to the metadata, wherever the book was downloaded to
// Returns null when there is no usable copy (not downloaded, SD card removed, Electron download)
async function epubLoadOfflineCopy(bookId) {
	if (typeof obLoadOfflineBook != 'function' || !obIsBookCompleted(bookId)) return null;
	try {
		const offline = await obLoadOfflineBook(bookId);
		if (!offline?.bookPath) return null;
		const res = await fetch(`${offline.bookPath}/book.epub`);
		if (!res.ok) return null;
		return await res.arrayBuffer();
	} catch (err) {
		console.warn('EPUB offline copy not readable:', err);
		return null;
	}
}

//MBX Reader UI

function epubBuildUI() {
	if (epub.ui) return epub.ui;

	const root = document.createElement('div');
	root.id = 'epubReader';
	root.className = 'epub-reader epub-hidden';
	root.innerHTML = `
		<div class="epub-viewer" id="epubViewer"></div>
		<div class="epub-loading" id="epubLoading"><span id="epubLoadingText"></span></div>
		<div class="epub-bar epub-top" id="epubTop">
			<button class="epub-btn" id="epubClose"><span class="ico icon-x"></span></button>
			<div class="epub-title" id="epubTitle"></div>
			<button class="epub-btn" id="epubFontDown">A-</button>
			<button class="epub-btn" id="epubFontUp">A+</button>
			<button class="epub-btn" id="epubTheme"><span class="ico icon-sun-moon"></span></button>
		</div>
		<div class="epub-bar epub-bottom" id="epubBottom">
			<select class="epub-toc" id="epubToc"></select>
			<div class="epub-progress" id="epubProgress"></div>
		</div>`;
	document.body.append(root);

	const ui = {
		root,
		viewer: root.querySelector('#epubViewer'),
		loading: root.querySelector('#epubLoading'),
		loadingText: root.querySelector('#epubLoadingText'),
		title: root.querySelector('#epubTitle'),
		toc: root.querySelector('#epubToc'),
		progress: root.querySelector('#epubProgress'),
	};

	root.querySelector('#epubClose').addEventListener('click', () => epubReaderExit());
	root.querySelector('#epubFontDown').addEventListener('click', () => epubChangeFont(-10));
	root.querySelector('#epubFontUp').addEventListener('click', () => epubChangeFont(10));
	root.querySelector('#epubTheme').addEventListener('click', () => epubCycleTheme());
	ui.toc.addEventListener('change', () => {
		if (ui.toc.value && epub.rendition) epub.rendition.display(ui.toc.value);
		epubShowBars(false);
	});

	document.addEventListener('keydown', (event) => {
		if (!epub.active) return;
		epubHandleKey(event);
	});

	epub.ui = ui;
	return ui;
}

function epubShowBars(show) {
	epub.ui?.root.classList.toggle('epub-bars-visible', show);
}

function epubLoading(text) {
	if (!epub.ui) return;
	epub.ui.loading.classList.toggle('epub-hidden', text == null);
	epub.ui.loadingText.textContent = text ?? '';
}

function epubApplyTheme() {
	const theme = epubPref('Theme', 'light');
	epub.ui.root.dataset.theme = theme;
	epub.rendition?.themes.select(theme);
}

function epubCycleTheme() {
	const current = EPUB_THEME_ORDER.indexOf(epubPref('Theme', 'light'));
	epubSetPref('Theme', EPUB_THEME_ORDER[(current + 1) % EPUB_THEME_ORDER.length]);
	epubApplyTheme();
}

function epubChangeFont(delta) {
	const size = Math.min(250, Math.max(60, Number(epubPref('FontSize', 100)) + delta));
	epubSetPref('FontSize', size);
	epub.rendition?.themes.fontSize(`${size}%`);
}

// 'left' / 'right' as seen on screen, mapped through the book reading direction
function epubReaderTurn(side) {
	if (!epub.rendition) return;
	const forward = (side == 'right') != epub.rtl;
	forward ? epub.rendition.next() : epub.rendition.prev();
	epubShowBars(false);
}

function epubHandleKey(event) {
	switch (event.key) {
		case 'ArrowLeft':
			event.preventDefault();
			epubReaderTurn('left');
			break;
		case 'ArrowRight':
			event.preventDefault();
			epubReaderTurn('right');
			break;
		case 'PageDown':
		case ' ':
			event.preventDefault();
			epub.rendition?.next();
			break;
		case 'PageUp':
			event.preventDefault();
			epub.rendition?.prev();
			break;
		case 'Escape':
			event.preventDefault();
			epubReaderExit();
			break;
	}
}

// Event coordinates from inside the book iframe, in main window pixels
function epubEventX(event) {
	const frame = event.view?.frameElement;
	const rect = frame ? frame.getBoundingClientRect() : { left: 0 };
	const point = event.changedTouches?.[0] ?? event;
	return { x: rect.left + point.clientX, y: (frame ? frame.getBoundingClientRect().top : 0) + point.clientY };
}

function epubHandleTap(event) {
	// Taps on links keep their default behavior
	if (event.target?.closest?.('a')) return;
	const { x } = epubEventX(event);
	const width = window.innerWidth;
	if (x < width * 0.3) epubReaderTurn('left');
	else if (x > width * 0.7) epubReaderTurn('right');
	else epubShowBars(!epub.ui.root.classList.contains('epub-bars-visible'));
}

function epubTouchStart(event) {
	const { x, y } = epubEventX(event);
	epub.touchX = x;
	epub.touchY = y;
	epub.touchT = Date.now();
}

function epubTouchEnd(event) {
	const { x, y } = epubEventX(event);
	const dx = x - epub.touchX;
	const dy = y - epub.touchY;
	if (Date.now() - epub.touchT > 800 || Math.abs(dx) < 50 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
	// Swipe to the left shows what is on the right
	epubReaderTurn(dx < 0 ? 'right' : 'left');
	event.preventDefault?.();
}

function epubFillToc(toc) {
	const select = epub.ui.toc;
	select.innerHTML = '';
	const add = (items, depth) => items.forEach(item => {
		const option = document.createElement('option');
		option.value = item.href;
		option.textContent = '  '.repeat(depth) + (item.label || '').trim();
		select.append(option);
		if (item.subitems?.length) add(item.subitems, depth + 1);
	});
	const first = document.createElement('option');
	first.value = '';
	first.textContent = t('epub.toc');
	select.append(first);
	add(toc || [], 0);
}

function epubUpdateProgress(location) {
	const book = epub.book;
	const parts = [];
	const displayed = location.start.displayed;
	if (displayed?.total) parts.push(`${displayed.page} / ${displayed.total}`);
	if (book.locations.length()) {
		parts.push(`${Math.round(book.locations.percentageFromCfi(location.start.cfi) * 100)}%`);
	}
	epub.ui.progress.textContent = parts.join(' · ');
}

//MBX Reading position

function epubSectionPath(section) {
	return decodeURIComponent((section?.url || section?.href || '').replace(/^\//, ''));
}

// Komga matches the progression to one of its positions: 1 position per 1024 bytes of the chapter
function epubSnapProgression(path, progression) {
	const entry = epub.book.archive?.zip?.file(path);
	const size = entry?._data?.uncompressedSize;
	const count = size ? Math.max(1, Math.ceil(size / 1024)) : 1;
	return Math.min(count - 1, Math.max(0, Math.floor(progression * count))) / count;
}

function epubCurrentProgression(location) {
	const section = epub.book.spine.get(location.start.index);
	const path = epubSectionPath(section);
	const displayed = location.start.displayed;
	const inChapter = displayed?.total > 1 ? (displayed.page - 1) / displayed.total : 0;
	const total = epub.book.locations.length() ? epub.book.locations.percentageFromCfi(location.start.cfi) : null;
	return {
		modified: new Date().toISOString(),
		device: { id: epubDeviceId(), name: 'MangaBox' },
		locator: {
			href: epubEncodePath(path),
			type: section?.properties?.includes?.('svg') ? 'image/svg+xml' : 'application/xhtml+xml',
			locations: {
				progression: epubSnapProgression(path, inChapter),
				...(total != null && { totalProgression: total }),
			},
		},
	};
}

async function epubPutProgression(bookId, progression) {
	const req = await buildRequest('PUT', JSON.stringify(progression));
	let res = await fetch(`${mb.baseUrl}/api/v1/books/${bookId}/progression`, req);
	if (res.status == 400) {
		// Position not matched (unknown chapter size): the chapter start always matches
		progression.locator.locations.progression = 0;
		res = await fetch(`${mb.baseUrl}/api/v1/books/${bookId}/progression`, await buildRequest('PUT', JSON.stringify(progression)));
	}
	// 409: the server already has a newer position, nothing to do
	return res.ok || res.status == 409 || res.status == 400;
}

function epubLoadPending() {
	try {
		return JSON.parse(localStorage.getItem(epubPendingKey()) ?? '{}');
	} catch {
		return {};
	}
}

function epubSavePending(pending) {
	try {
		if (Object.keys(pending).length) localStorage.setItem(epubPendingKey(), JSON.stringify(pending));
		else localStorage.removeItem(epubPendingKey());
	} catch { }
}

async function epubSyncBook(bookId, progression, completed) {
	const pending = epubLoadPending();
	pending[bookId] = { progression, completed };
	epubSavePending(pending);
	if (!offlineSession) await epubFlushPending();
}

// Called on boot and from obRefreshReadStatus: sends positions saved while offline
async function epubFlushPending() {
	if (offlineSession || !mb.currentUserId) return;
	const pending = epubLoadPending();
	for (const [bookId, item] of Object.entries(pending)) {
		try {
			if (await epubPutProgression(bookId, item.progression)) {
				if (item.completed) {
					await fetch(`${mb.baseUrl}/api/v1/books/${bookId}/read-progress`, await buildRequest('PATCH', JSON.stringify({ completed: true })));
				}
				delete pending[bookId];
			}
		} catch (err) {
			console.warn('EPUB progression sync failed, kept for later:', err);
			break;
		}
	}
	epubSavePending(pending);
}

function epubOnRelocated(location) {
	epub.location = location;
	epubUpdateProgress(location);
	try {
		localStorage.setItem(epubPosKey(epub.bookId), JSON.stringify({ cfi: location.start.cfi, t: Date.now() }));
	} catch { }

	clearTimeout(epub.syncTimer);
	const bookId = epub.bookId;
	epub.syncTimer = setTimeout(() => {
		if (epub.bookId != bookId || !epub.location) return;
		epubSyncBook(bookId, epubCurrentProgression(epub.location), !!location.atEnd);
	}, 1500);
}

async function epubServerProgression(bookId) {
	if (offlineSession) return null;
	try {
		const res = await fetch(`${mb.baseUrl}/api/v1/books/${bookId}/progression`, await buildRequest('GET', null));
		if (res.status != 200) return null;
		return await res.json();
	} catch {
		return null;
	}
}

// Opens the chapter and moves to the page matching the saved progression
async function epubDisplayProgression(progression) {
	const path = decodeURIComponent(progression?.locator?.href || '').split('#')[0];
	const section = path && epub.book.spine.spineItems.find(item => epubSectionPath(item) == path);
	if (!section) return false;
	await epub.rendition.display(section.href);
	const fraction = progression.locator.locations?.progression ?? 0;
	const location = epub.rendition.currentLocation();
	const total = location?.start?.displayed?.total ?? 1;
	// Komga positions are coarser than pages and were snapped down when saved: round back
	const target = Math.min(total, Math.round(fraction * total) + 1);
	for (let page = 1; page < target; page++) await epub.rendition.next();
	return true;
}

async function epubDisplayStart(bookId) {
	let local = null;
	try {
		local = JSON.parse(localStorage.getItem(epubPosKey(bookId)) ?? 'null');
	} catch { }
	const server = await epubServerProgression(bookId);
	const serverTime = server?.modified ? Date.parse(server.modified) : 0;
	const serverIsOurs = server?.device?.id == epubDeviceId();

	// The local position is exact (CFI), the server one wins only when another device read further
	if (server?.locator?.href && (!local || (!serverIsOurs && serverTime > local.t))) {
		if (await epubDisplayProgression(server)) return;
	}
	if (local?.cfi) {
		try {
			await epub.rendition.display(local.cfi);
			return;
		} catch { }
	}
	await epub.rendition.display();
}

//MBX Open / close

// Book object passed to navigateToReadBook, used to avoid fetching it again
let epubKnownBook = null;
function epubRememberBook(book) {
	epubKnownBook = book?.media ? book : null;
}

// Returns true when the book was opened here, false to let the image reader handle it
async function epubReaderOpen(bookId) {
	const offlineBook = obIsBookCompleted?.(bookId);
	let bookData = epubKnownBook?.id == bookId ? epubKnownBook : null;
	epubKnownBook = null;
	if (!bookData && offlineSession && offlineBook) {
		bookData = (await obLoadOfflineBook(bookId))?.bookMetadata ?? null;
	}
	bookData ??= await callAPI(`c_bookdata${bookId}`, `/api/v1/books/${bookId}`);
	if (!epubIsReflowable(bookData)) return false;

	const token = ++epub.openToken;
	const ui = epubBuildUI();
	epubDestroyBook();
	epub.active = true;
	epub.bookId = bookId;
	ui.root.classList.remove('epub-hidden');
	ui.title.textContent = bookData.metadata?.title ?? bookData.name ?? '';
	ui.progress.textContent = '';
	ui.toc.innerHTML = '';
	epubShowBars(true);
	epubLoading(t('epub.loading'));
	epubApplyTheme();

	if (typeof isSystemBars != 'undefined' && isSystemBars) Capacitor.Plugins.SystemBars.hide();
	if (typeof isVolumeButtons != 'undefined' && isVolumeButtons) initVolumeButtons();
	if (typeof isKeepAwake != 'undefined' && isKeepAwake) Capacitor.Plugins.CapacitorKeepAwake.keepAwake();

	try {
		let data = offlineBook ? await epubLoadOfflineCopy(bookId) : null;
		if (!data) {
			if (offlineSession) throw new Error(t('epub.notoffline'));
			data = await epubFetchArrayBuffer(bookId, (fraction) => epubLoading(`${t('epub.loading')} ${Math.round(fraction * 100)}%`));
		}
		if (token != epub.openToken) return true;

		const book = ePub(data);
		epub.book = book;
		await book.ready;
		if (token != epub.openToken) return true;

		epub.rtl = book.packaging?.metadata?.direction == 'rtl';
		const rendition = book.renderTo(ui.viewer, {
			width: '100%',
			height: '100%',
			flow: 'paginated',
			spread: 'auto',
			minSpreadWidth: 900,
			allowScriptedContent: false,
		});
		epub.rendition = rendition;
		Object.entries(EPUB_THEMES).forEach(([name, styles]) => rendition.themes.register(name, styles));
		rendition.themes.fontSize(`${epubPref('FontSize', 100)}%`);
		epubApplyTheme();

		rendition.on('relocated', epubOnRelocated);
		rendition.on('click', epubHandleTap);
		rendition.on('touchstart', epubTouchStart);
		rendition.on('touchend', epubTouchEnd);
		rendition.on('keydown', epubHandleKey);

		epubFillToc(book.navigation?.toc);
		await epubDisplayStart(bookId);
		epubLoading(null);
		setTimeout(() => epubShowBars(false), 1500);

		// Book wide percentage, computed in the background once the first page is shown,
		// so it does not compete with opening the book on a slow device
		setTimeout(() => {
			if (epub.book != book) return;
			book.locations.generate(1600).then(() => {
				if (epub.book == book && epub.location) epubUpdateProgress(epub.location);
			}).catch(() => { });
		}, 2500);
	} catch (err) {
		console.error('EPUB open failed:', err);
		if (token != epub.openToken) return true;
		epubLoading(null);
		showModal('', false, `<span>${t('epub.error')}</span><br><small>${String(err.message ?? err).replace(/</g, '&lt;')}</small>`, [{
			label: 'modal.ok', runfunction: () => {
				closeModal();
				setTimeout(epubReaderExit, 250);
			}, high: true
		}]);
	}
	return true;
}

function epubDestroyBook() {
	clearTimeout(epub.syncTimer);
	if (epub.location && epub.bookId) {
		// Last position, sent right away instead of waiting for the timer
		epubSyncBook(epub.bookId, epubCurrentProgression(epub.location), !!epub.location.atEnd);
	}
	try {
		epub.rendition?.destroy();
		epub.book?.destroy();
	} catch (err) {
		console.warn('EPUB cleanup:', err);
	}
	epub.rendition = null;
	epub.book = null;
	epub.location = null;
	if (epub.ui) epub.ui.viewer.innerHTML = '';
}

// Called from loadContent on every navigation: closes the reader when leaving it
function epubReaderClose() {
	if (!epub.active) return;
	epub.openToken++;
	epubDestroyBook();
	epub.active = false;
	epub.bookId = null;
	epub.ui.root.classList.add('epub-hidden');
	if (typeof isSystemBars != 'undefined' && isSystemBars) Capacitor.Plugins.SystemBars.show();
	if (typeof isVolumeButtons != 'undefined' && isVolumeButtons) clearVolumeButtons();
	if (typeof isKeepAwake != 'undefined' && isKeepAwake) Capacitor.Plugins.CapacitorKeepAwake.allowSleep();
}

// Close button: same history handling as the image reader
function epubReaderExit() {
	epubReaderClose();
	if (mb.historyStack.length == 1) {
		mb.historyStack = ['#dashboard', mb.historyStack[0]];
	}
	goBack();
}
