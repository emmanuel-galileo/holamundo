/**
 * Telemetry HUD manager. Updates DOM elements with real-time metrics
 * (CWND, AIMD phase, LRU memory, FPS, bandwidth, and camera coordinates).
 * Controls EarthCam collapsible telemetry panel and corner FPS pill.
 */
export class TelemetryHud {
    constructor() {
        this.dom = {
            ctrlStatus: document.getElementById('ctrl-indicator'),
            dataStatus: document.getElementById('data-indicator'),
            cwndValue: document.getElementById('stat-cwnd'),
            cwndPhase: document.getElementById('stat-phase'),
            cwndBar: document.getElementById('bar-cwnd'),
            ssthresh: document.getElementById('stat-ssthresh'),
            rtt: document.getElementById('stat-rtt'),
            baseRtt: document.getElementById('stat-basertt'),
            diff: document.getElementById('stat-diff'),
            pending: document.getElementById('stat-pending'),
            cacheCount: document.getElementById('stat-cache-count'),
            cacheBar: document.getElementById('bar-cache'),
            evictions: document.getElementById('stat-evictions'),
            zoom: document.getElementById('stat-zoom'),
            epoch: document.getElementById('stat-epoch'),
            coords: document.getElementById('stat-coords'),
            fps: document.getElementById('stat-fps'),
            fpsPill: document.getElementById('fps-pill'),
            fpsPillVal: document.getElementById('stat-fps-pill'),
            bytes: document.getElementById('stat-bytes'),
            visibleTiles: document.getElementById('stat-visible-tiles'),
            toast: document.getElementById('toast-message'),
            telemetryPanel: document.getElementById('telemetry-panel'),
            btnToggleTelemetry: document.getElementById('btn-toggle-telemetry'),
            btnCloseTelemetry: document.getElementById('btn-close-telemetry')
        };

        // FPS tracking
        this.lastFrameTime = performance.now();
        this.frameCount = 0;
        this.currentFps = 60;
        this.lastFpsUpdate = performance.now();

        this.toastTimeout = null;

        this.bindEvents();
    }

    bindEvents() {
        if (this.dom.btnCloseTelemetry) {
            this.dom.btnCloseTelemetry.addEventListener('click', () => this.toggleTelemetryPanel());
        }
    }

    /**
     * Toggles the telemetry panel visibility and synchronizes the corner FPS pill.
     */
    toggleTelemetryPanel() {
        if (!this.dom.telemetryPanel) return;

        const isHidden = this.dom.telemetryPanel.classList.toggle('hidden');

        if (this.dom.fpsPill) {
            this.dom.fpsPill.classList.toggle('hidden', !isHidden);
        }

        if (this.dom.btnToggleTelemetry) {
            this.dom.btnToggleTelemetry.classList.toggle('active', !isHidden);
        }
    }

    /**
     * Orchestrator: Updates HUD elements with latest frame stats.
     */
    updateFrame(viewport, cache, protocol, visibleTilesCount) {
        this.computeFps();
        this.updateCwndMetrics(protocol);
        this.updateCacheMetrics(cache);
        this.updateCameraMetrics(viewport, protocol.currentEpoch, visibleTilesCount);
        this.updateNetworkMetrics(protocol.totalBytesReceived);
    }

    // --- Sub-functions (Single-responsibility) ---

    computeFps() {
        this.frameCount++;
        const now = performance.now();
        const elapsed = now - this.lastFpsUpdate;

        if (elapsed >= 500) {
            this.currentFps = Math.round((this.frameCount * 1000) / elapsed);

            if (this.dom.fps) {
                this.dom.fps.textContent = this.currentFps;
            }
            if (this.dom.fpsPillVal) {
                this.dom.fpsPillVal.textContent = this.currentFps;
            }

            this.frameCount = 0;
            this.lastFpsUpdate = now;
        }
    }

    updateCwndMetrics(protocol) {
        if (!this.dom.cwndValue) return;

        const cwnd = protocol.lastCwnd;
        const rtt = protocol.rtt || 0;
        const baseRtt = protocol.baseRtt || 0;
        const diff = (protocol.diff !== undefined) ? protocol.diff : 0.0;
        const pending = (protocol.pending !== undefined) ? protocol.pending : 0;

        this.dom.cwndValue.textContent = cwnd;

        if (this.dom.cwndPhase) {
            this.dom.cwndPhase.textContent = 'TCP VEGAS';
            this.dom.cwndPhase.className = 'metric-tag tcp-vegas';
        }

        if (this.dom.rtt) this.dom.rtt.textContent = rtt;
        if (this.dom.baseRtt) this.dom.baseRtt.textContent = baseRtt;
        if (this.dom.diff) this.dom.diff.textContent = typeof diff === 'number' ? diff.toFixed(2) : diff;
        if (this.dom.pending) this.dom.pending.textContent = pending;
        if (this.dom.ssthresh) this.dom.ssthresh.textContent = baseRtt;

        const percentage = Math.min(100, Math.round((cwnd / 256) * 100));
        if (this.dom.cwndBar) {
            this.dom.cwndBar.style.width = `${Math.max(3, percentage)}%`;
        }
    }

    updateCacheMetrics(cache) {
        if (!this.dom.cacheCount) return;

        const stats = cache.getStats();
        this.dom.cacheCount.textContent = `${stats.size} / ${stats.maxSize}`;
        this.dom.evictions.textContent = stats.evictions;

        const percentage = Math.min(100, Math.round((stats.size / stats.maxSize) * 100));
        this.dom.cacheBar.style.width = `${percentage}%`;
    }

    updateCameraMetrics(viewport, epoch, visibleTilesCount) {
        if (!this.dom.zoom) return;

        const level = viewport.getTileLevel();
        const scale = viewport.currentScale.toFixed(3);
        this.dom.zoom.textContent = `L${level} (×${scale})`;
        this.dom.epoch.textContent = epoch;
        this.dom.coords.textContent = `X: ${Math.round(viewport.camX)}, Y: ${Math.round(viewport.camY)}`;
        this.dom.visibleTiles.textContent = visibleTilesCount;
    }

    updateNetworkMetrics(bytesTotal) {
        if (!this.dom.bytes) return;
        this.dom.bytes.textContent = this.formatBytes(bytesTotal);
    }

    formatBytes(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1048576).toFixed(2) + ' MB';
    }

    setConnectionStatus(channel, isOnline) {
        const element = (channel === 'control') ? this.dom.ctrlStatus : this.dom.dataStatus;
        if (element) {
            element.className = isOnline ? 'status-pill online' : 'status-pill offline';
        }
    }

    showToast(message, durationMs = 2500) {
        if (!this.dom.toast) return;
        this.dom.toast.textContent = message;
        this.dom.toast.classList.remove('hidden');

        if (this.toastTimeout) clearTimeout(this.toastTimeout);
        this.toastTimeout = setTimeout(() => {
            this.dom.toast.classList.add('hidden');
        }, durationMs);
    }
}
