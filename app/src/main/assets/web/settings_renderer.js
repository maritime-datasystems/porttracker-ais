/**
 * Settings Renderer for PortTracker AIS
 * Handles fetching, rendering, and saving configuration via Admin API.
 */

var SettingsRenderer = {
    currentConfig: {},

    // Initialize the Settings View
    init: async function () {
        const container = document.getElementById('settings-content');
        if (!container) return;

        container.innerHTML = '<div class="text-center p-5"><div class="spinner-border text-primary" role="status"></div><br>Loading Configuration...</div>';

        try {
            const response = await fetch('/admin/api/config');
            if (!response.ok) throw new Error("Failed to fetch config");
            this.currentConfig = await response.json();
            // Also fetch live status to get SDR/service state
            try {
                const statusResp = await fetch('/admin/api/status');
                if (statusResp.ok) {
                    const status = await statusResp.json();
                    this.currentConfig = { ...this.currentConfig, ...status };
                }
            } catch (e2) { /* status fetch is best-effort */ }
            this.render();
            // A sign-in may be in flight from before a page reload (the
            // Custom Tab sign-in returns here via a WebView reload) — pick
            // its status back up so the card doesn't silently go blank.
            this.resumeAuthStatus();
        } catch (e) {
            const errorDiv = document.createElement('div');
            errorDiv.className = 'alert alert-danger';
            errorDiv.textContent = 'Error loading settings: ' + e.message;
            container.innerHTML = '';
            container.appendChild(errorDiv);
        }
    },

    // Render the Tabs and Forms
    render: function () {
        const container = document.getElementById('settings-content');
        container.innerHTML = `
            <ul class="nav nav-tabs" id="settingsTabs" role="tablist">
                <li class="nav-item">
                    <button class="nav-link active" id="tab-status" data-bs-toggle="tab" data-bs-target="#pane-status" type="button">Status</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-sdr" data-bs-toggle="tab" data-bs-target="#pane-sdr" type="button">SDR</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-networking" data-bs-toggle="tab" data-bs-target="#pane-networking" type="button">Networking</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-settings" data-bs-toggle="tab" data-bs-target="#pane-settings" type="button">Data Sharing</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-location" data-bs-toggle="tab" data-bs-target="#pane-location" type="button">Location</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-app" data-bs-toggle="tab" data-bs-target="#pane-app" type="button"><i class="bi bi-gear-wide-connected"></i> App</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-porttracker" data-bs-toggle="tab" data-bs-target="#pane-porttracker" type="button">Account</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-database" data-bs-toggle="tab" data-bs-target="#pane-database" type="button">Internal DB</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-control" data-bs-toggle="tab" data-bs-target="#pane-control" type="button">Control</button>
                </li>
            </ul>
            <div class="tab-content p-3 border border-top-0 rounded-bottom bg-white" id="settingsTabContent">
                <div class="tab-pane fade show active" id="pane-status">${this.renderStatusPane()}</div>
                <div class="tab-pane fade" id="pane-sdr">${this.renderSdrPane()}</div>
                <div class="tab-pane fade" id="pane-networking">${this.renderNetworkingPane()}</div>
                <div class="tab-pane fade" id="pane-settings">${this.renderSettingsPane()}</div>
                <div class="tab-pane fade" id="pane-location">${this.renderLocationPane()}</div>
                <div class="tab-pane fade" id="pane-app">${this.renderAppPane()}</div>
                <div class="tab-pane fade" id="pane-porttracker">${this.renderPorttrackerPane()}</div>
                <div class="tab-pane fade" id="pane-database">${this.renderDatabasePane()}</div>
                <div class="tab-pane fade" id="pane-control">${this.renderControlPane()}</div>
            </div>
            <div class="mt-3 text-end">
                <button class="btn btn-primary" onclick="SettingsRenderer.save()">
                    <i class="bi bi-save"></i> Save & Restart Service
                </button>
            </div>
        `;

        // Hook tab change events for auto-refresh
        const tabEls = container.querySelectorAll('button[data-bs-toggle="tab"]');
        tabEls.forEach(tab => {
            tab.addEventListener('shown.bs.tab', (e) => {
                if (e.target.id === 'tab-sdr') {
                    this.startSdrAutoRefresh();
                } else {
                    this.stopSdrAutoRefresh();
                }
                if (e.target.id === 'tab-porttracker') {
                    this.refreshGatewayStatus();
                }
            });
        });
    },

    // --- Pane Renderers ---

    renderStatusPane: function () {
        // This is a static view of the current config values deemed "Status" related
        const remoteEnabled = this.bool('pref_enable_remote');
        const mqttEnabled = this.bool('mqtt_enabled');
        const stationName = this.val('pref_station_name', '').trim();
        const sanitizedName = stationName.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
        const externalUrl = (remoteEnabled && sanitizedName) ? `http://${sanitizedName}.connect.porttracker.co` : '';
        return `
            <h5>Application Info</h5>
            <table class="table table-bordered">
                <tr><th>Version</th><td>${this.val('app_version', 'Unknown')}</td></tr>
                <tr><th>Device Type</th><td>${this.getDeviceTypeName(this.val('device_type', '1'))}</td></tr>
                <tr><th>Web Server Port</th><td>${this.val('pref_local_web_port', '8080')}</td></tr>
                <tr><th>Remote Access</th><td>${remoteEnabled ? '<span class="text-success">🟢 Enabled</span>' : '<span class="text-secondary">⚪ Disabled</span>'}</td></tr>
                ${externalUrl ? `<tr><th>External URL</th><td><a href="${externalUrl}" target="_blank" class="text-primary"><i class="bi bi-box-arrow-up-right"></i> ${externalUrl}</a></td></tr>` : ''}
                <tr><th>MQTT Publishing</th><td>${mqttEnabled ? '<span class="text-success">🟢 Enabled</span>' : '<span class="text-secondary">⚪ Disabled</span>'}</td></tr>
            </table>
        `;
    },

    renderSdrPane: function () {
        const serviceRunning = this.bool('service_running');
        const sdrConnected = this.bool('sdr_connected');
        const hasPermission = this.bool('sdr_permission');
        const msgCount = this.val('message_count', 0);
        const deviceName = this.val('sdr_device_name', 'No device');
        const usbDevices = this.val('usb_devices', []);

        const serviceStatusClass = serviceRunning ? 'text-success' : 'text-secondary';
        const serviceStatusText = serviceRunning ? '🟢 Running' : '⚪ Stopped';

        const sdrStatusClass = sdrConnected ? 'text-success' : 'text-danger';
        const sdrStatusText = sdrConnected ? '✅ Connected' : '❌ Not Connected';

        const permissionClass = hasPermission ? 'text-success' : 'text-warning';
        const permissionText = hasPermission ? '✅ Granted' : '⚠️ Not Granted';

        // Build USB device list
        let usbListHtml = '';
        if (Array.isArray(usbDevices) && usbDevices.length > 0) {
            usbListHtml = usbDevices.map(dev => `
                <tr>
                    <td>${dev.name || 'Unknown'}</td>
                    <td><code>${dev.vendorId || '?'}:${dev.productId || '?'}</code></td>
                    <td>${dev.isSDR ? '<span class="badge bg-primary">SDR</span>' : ''}</td>
                </tr>
            `).join('');
        } else {
            usbListHtml = '<tr><td colspan="3" class="text-muted">No USB devices detected</td></tr>';
        }

        return `
            <h5><i class="bi bi-broadcast"></i> SDR Device Status</h5>
            
            <!-- Service Control -->
            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <h6 class="mb-1">SDR Service</h6>
                            <span class="${serviceStatusClass}" id="sdr_service_status">${serviceStatusText}</span>
                        </div>
                        <div class="form-check form-switch">
                            <input class="form-check-input" type="checkbox" id="service_enabled" 
                                ${serviceRunning ? 'checked' : ''} 
                                onchange="SettingsRenderer.toggleService(this.checked)">
                            <label class="form-check-label" for="service_enabled">Enable</label>
                        </div>
                    </div>
                </div>
            </div>
            
            <!-- SDR Device Info -->
            <table class="table table-bordered">
                <tr>
                    <th style="width:40%">AIS Device</th>
                    <td id="sdr_device_name"><strong>${deviceName}</strong></td>
                </tr>
                <tr>
                    <th>SDR Status</th>
                    <td id="sdr_connected">${sdrStatusText}</td>
                </tr>
                <tr>
                    <th>USB Permission</th>
                    <td id="sdr_usb_permission">${permissionText}</td>
                </tr>
                <tr>
                    <th>Messages Received</th>
                    <td><span class="badge bg-info fs-6" id="sdr_msg_count">${msgCount}</span></td>
                </tr>
            </table>
            
            <!-- Connected USB Devices -->
            <h6 class="mt-4"><i class="bi bi-usb-symbol"></i> Connected USB Devices</h6>
            <table class="table table-sm table-striped">
                <thead>
                    <tr>
                        <th>Device Name</th>
                        <th>VID:PID</th>
                        <th>Type</th>
                    </tr>
                </thead>
                <tbody id="usb_device_list">
                    ${usbListHtml}
                </tbody>
            </table>
            
            <div class="d-flex gap-2 flex-wrap">
                <button class="btn btn-outline-secondary btn-sm" onclick="SettingsRenderer.refreshSdrStatus()">
                    <i class="bi bi-arrow-clockwise"></i> Refresh Status
                </button>
                <button class="btn btn-outline-primary btn-sm" onclick="SettingsRenderer.requestUsbPermission()">
                    <i class="bi bi-shield-check"></i> Request USB Permission
                </button>
                <button class="btn btn-outline-success btn-sm" onclick="SettingsRenderer.restartEngine()">
                    <i class="bi bi-play-circle"></i> Restart SDR Engine
                </button>
            </div>
            
            <hr>
            <h5 class="mt-4"><i class="bi bi-gear"></i> SDR Configuration</h5>
            <div class="mb-3">
                <label class="form-label">Device Type</label>
                <select class="form-select" id="device_type">
                    <option value="1" ${this.val('device_type') === '1' ? 'selected' : ''}>RTL-SDR</option>
                    <option value="0" ${this.val('device_type') === '0' ? 'selected' : ''}>RTL-TCP</option>
                    <option value="2" ${this.val('device_type') === '2' ? 'selected' : ''}>AirSpy</option>
                    <option value="3" ${this.val('device_type') === '3' ? 'selected' : ''}>AirSpy HF+</option>
                    <option value="4" ${this.val('device_type') === '4' ? 'selected' : ''}>SpyServer</option>
                </select>
            </div>
            <div class="mb-3">
                <label class="form-label">PPM Correction</label>
                <input type="number" class="form-control" id="frequency_correction" value="${this.val('frequency_correction', '0')}">
                <div class="form-text">Frequency correction in Parts Per Million (ppm)</div>
            </div>
            
            <hr>
            <h5 class="mt-4"><i class="bi bi-ethernet"></i> TCP Listener</h5>
            <div class="card mb-3">
                <div class="card-body">
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="tcp_enabled" ${this.bool('tcp_enabled') ? 'checked' : ''}>
                        <label class="form-check-label" for="tcp_enabled">Enable TCP Listener</label>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">TCP Port</label>
                        <input type="number" class="form-control" id="tcp_port" value="${this.val('tcp_port', '10111')}">
                    </div>
                </div>
            </div>
        `;
    },

    renderNetworkingPane: function () {
        const remoteEnabled = this.bool('pref_enable_remote');
        const stationName = this.val('pref_station_name', '').trim();
        const sanitizedName = stationName.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
        const externalUrl = (remoteEnabled && sanitizedName) ? `https://${sanitizedName}.connect.porttracker.co` : '';
        return `
            <h5>Remote Access</h5>
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="pref_enable_remote" ${remoteEnabled ? 'checked' : ''}>
                <label class="form-check-label" for="pref_enable_remote">Enable External Web Portal</label>
            </div>
            <div class="mb-3">
                <label class="form-label">Station Name</label>
                <input type="text" class="form-control" id="pref_station_name" value="${this.val('pref_station_name')}">
                <div class="form-text">Unique name for your station (e.g. my-boat-1). This is used as the subdomain for remote access.</div>
            </div>
            ${externalUrl ? `
            <div class="alert alert-success d-flex align-items-center" role="alert">
                <i class="bi bi-globe2 me-2" style="font-size: 1.2rem;"></i>
                <div>
                    <strong>External Portal Active</strong><br>
                    <a href="${externalUrl}" target="_blank" class="alert-link">${externalUrl}</a>
                </div>
            </div>` : (remoteEnabled && !sanitizedName ? `
            <div class="alert alert-warning d-flex align-items-center" role="alert">
                <i class="bi bi-exclamation-triangle me-2"></i>
                <div>Set a <strong>Station Name</strong> above to enable remote access.</div>
            </div>` : '')}
            <hr>
            <h5>Local Network</h5>
             <div class="mb-3">
                <label class="form-label">Local Web Port</label>
                <input type="number" class="form-control" id="pref_local_web_port" value="${this.val('pref_local_web_port', '8080')}">
            </div>
             <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="webviewer_enabled" ${this.bool('webviewer_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="webviewer_enabled">Enable Web Server</label>
            </div>
        `;
    },

    renderSettingsPane: function () {
        let html = `
            <h5><i class="bi bi-share"></i> UDP Outputs</h5>
        `;

        for (let i = 1; i <= 4; i++) {
            html += `
                <div class="card mb-2">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>UDP Output ${i}</span>
                        <div class="form-check form-switch">
                            <input class="form-check-input" type="checkbox" id="udp${i}_enabled" ${this.bool(`udp${i}_enabled`) ? 'checked' : ''}>
                        </div>
                    </div>
                    <div class="card-body row icon-box">
                        <div class="col-md-8">
                            <label class="form-label">Host</label>
                            <input type="text" class="form-control" id="udp${i}_host" value="${this.val(`udp${i}_host`, '127.0.0.1')}">
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">Port</label>
                            <input type="number" class="form-control" id="udp${i}_port" value="${this.val(`udp${i}_port`, 10109 + i)}">
                        </div>
                        <div class="col-12 mt-2">
                            <div class="form-check">
                                <input class="form-check-input" type="checkbox" id="udp${i}_json" ${this.bool(`udp${i}_json`) ? 'checked' : ''}>
                                <label class="form-check-label" for="udp${i}_json">JSON format</label>
                            </div>
                        </div>
                    </div>
                </div>
            `;
        }

        html += `
            <hr>
            <h5><i class="bi bi-broadcast"></i> Chartplotter Output</h5>
            <p class="text-muted small">
                Make this station appear as an AIS + GPS instrument on your network,
                for OpenCPN, Navionics and other chartplotter apps. Choose classic
                NMEA 0183 sentences or NMEA 2000 emulation (Yacht Devices RAW or
                SeaSmart $PCDIN framing).
            </p>
            <div class="card mb-3">
                <div class="card-header d-flex justify-content-between align-items-center">
                    <span>NMEA Network Output</span>
                    <div class="form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="nmea_out_enabled" ${this.bool('nmea_out_enabled') ? 'checked' : ''}>
                    </div>
                </div>
                <div class="card-body">
                    <div class="mb-3">
                        <label class="form-label">Protocol</label>
                        <select class="form-select" id="nmea_out_format">
                            <option value="nmea0183" ${this.val('nmea_out_format', 'nmea0183') === 'nmea0183' ? 'selected' : ''}>NMEA 0183 (AIS !AIVDM + GPS RMC/GGA)</option>
                            <option value="n2k-ydraw" ${this.val('nmea_out_format', 'nmea0183') === 'n2k-ydraw' ? 'selected' : ''}>NMEA 2000 — Yacht Devices RAW (experimental)</option>
                            <option value="n2k-pcdin" ${this.val('nmea_out_format', 'nmea0183') === 'n2k-pcdin' ? 'selected' : ''}>NMEA 2000 — SeaSmart $PCDIN (experimental)</option>
                        </select>
                    </div>
                    <div class="row mb-2">
                        <div class="col-md-6">
                            <div class="form-check form-switch">
                                <input class="form-check-input" type="checkbox" id="nmea_out_tcp_enabled" ${this.bool('nmea_out_tcp_enabled', true) ? 'checked' : ''}>
                                <label class="form-check-label" for="nmea_out_tcp_enabled">TCP server (apps connect to this phone)</label>
                            </div>
                        </div>
                        <div class="col-md-6">
                            <label class="form-label">TCP Port</label>
                            <input type="number" class="form-control" id="nmea_out_tcp_port" value="${this.val('nmea_out_tcp_port', '10110')}">
                        </div>
                    </div>
                    <div class="row mb-2">
                        <div class="col-md-4">
                            <div class="form-check form-switch">
                                <input class="form-check-input" type="checkbox" id="nmea_out_udp_enabled" ${this.bool('nmea_out_udp_enabled') ? 'checked' : ''}>
                                <label class="form-check-label" for="nmea_out_udp_enabled">UDP broadcast</label>
                            </div>
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">UDP Target</label>
                            <input type="text" class="form-control" id="nmea_out_udp_host" value="${this.val('nmea_out_udp_host', '255.255.255.255')}">
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">UDP Port</label>
                            <input type="number" class="form-control" id="nmea_out_udp_port" value="${this.val('nmea_out_udp_port', '10110')}">
                        </div>
                    </div>
                    <div class="form-check form-switch mb-2">
                        <input class="form-check-input" type="checkbox" id="nmea_out_gps" ${this.bool('nmea_out_gps', true) ? 'checked' : ''}>
                        <label class="form-check-label" for="nmea_out_gps">Include this phone's position as own-ship GPS</label>
                    </div>
                    <p class="text-muted mb-0" style="font-size:0.8em">
                        In your chartplotter app add a network connection to this
                        phone's IP, e.g. TCP port 10110 (255.255.255.255 sends a UDP
                        broadcast to the whole WiFi network).
                    </p>
                </div>
            </div>

            <h5>GPSD Forwarding</h5>
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="gpsd_enabled" ${this.bool('gpsd_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="gpsd_enabled">Enable GPSD Forwarding</label>
            </div>
             <div class="row">
                <div class="col-md-8">
                    <label class="form-label">GPSD Host</label>
                    <input type="text" class="form-control" id="gpsd_host" value="${this.val('gpsd_host', '127.0.0.1')}">
                </div>
                <div class="col-md-4">
                    <label class="form-label">GPSD Port</label>
                    <input type="number" class="form-control" id="gpsd_port" value="${this.val('gpsd_port', '2947')}">
                </div>
            </div>
            <div class="mb-3 mt-3">
                <label class="form-label">Update Interval</label>
                <select class="form-select" id="gpsd_interval">
                    <option value="1" ${this.val('gpsd_interval', '10') === '1' ? 'selected' : ''}>1s</option>
                    <option value="2" ${this.val('gpsd_interval', '10') === '2' ? 'selected' : ''}>2s</option>
                    <option value="5" ${this.val('gpsd_interval', '10') === '5' ? 'selected' : ''}>5s</option>
                    <option value="10" ${this.val('gpsd_interval', '10') === '10' ? 'selected' : ''}>10s</option>
                    <option value="30" ${this.val('gpsd_interval', '10') === '30' ? 'selected' : ''}>30s</option>
                    <option value="60" ${this.val('gpsd_interval', '10') === '60' ? 'selected' : ''}>60s</option>
                </select>
            </div>
        `;

        return html;
    },

    renderLocationPane: function () {
        const installationType = this.val('installation_type', 'moving');
        const isFixed = installationType === 'fixed';
        const vehicleType = this.val('vehicle_type', 'boat');
        const isBoat = vehicleType === 'boat';

        return `
            <h5><i class="bi bi-geo-alt"></i> Installation Location</h5>
            <p class="text-muted small">Configure where your AIS receiver is installed.</p>
            
            <!-- Installation Type Selection -->
            <div class="mb-3">
                <label class="form-label"><strong>Installation Type</strong></label>
                <div class="btn-group w-100" role="group">
                    <input type="radio" class="btn-check" name="installation_type" id="installation_fixed" value="fixed" 
                        ${isFixed ? 'checked' : ''} onchange="SettingsRenderer.updateLocationFields()">
                    <label class="btn btn-outline-primary" for="installation_fixed">
                        <i class="bi bi-building"></i> Fixed Installation
                    </label>
                    <input type="radio" class="btn-check" name="installation_type" id="installation_moving" value="moving" 
                        ${!isFixed ? 'checked' : ''} onchange="SettingsRenderer.updateLocationFields()">
                    <label class="btn btn-outline-primary" for="installation_moving">
                        <i class="bi bi-arrows-move"></i> Moving Installation
                    </label>
                </div>
            </div>
            
            <!-- Fixed Installation Fields -->
            <div id="fixed_installation_fields" style="display: ${isFixed ? 'block' : 'none'}">
                <div class="card mb-3">
                    <div class="card-header"><i class="bi bi-pin-map"></i> Fixed Location</div>
                    <div class="card-body">
                        <div class="row mb-3">
                            <div class="col-md-6">
                                <label class="form-label">Latitude</label>
                                <input type="number" step="0.000001" class="form-control" id="fixed_latitude" 
                                    value="${this.val('fixed_latitude', '')}" placeholder="e.g. 53.551086">
                            </div>
                            <div class="col-md-6">
                                <label class="form-label">Longitude</label>
                                <input type="number" step="0.000001" class="form-control" id="fixed_longitude" 
                                    value="${this.val('fixed_longitude', '')}" placeholder="e.g. 9.993682">
                            </div>
                        </div>
                        <div id="location_map" style="height: 250px; background: #e9ecef; border-radius: 8px; display: flex; align-items: center; justify-content: center;">
                            <div class="text-center text-muted">
                                <i class="bi bi-map" style="font-size: 2rem;"></i>
                                <p class="mb-1">Interactive Map</p>
                                <button class="btn btn-sm btn-outline-primary" onclick="SettingsRenderer.openMapPicker()">
                                    <i class="bi bi-crosshair"></i> Pick Location on Map
                                </button>
                            </div>
                        </div>
                        <div class="form-text mt-2">Click the map or enter coordinates manually.</div>
                    </div>
                </div>
            </div>
            
            <!-- Moving Installation Fields -->
            <div id="moving_installation_fields" style="display: ${!isFixed ? 'block' : 'none'}">
                <div class="card mb-3">
                    <div class="card-header"><i class="bi bi-truck"></i> Moving Platform</div>
                    <div class="card-body">
                        <div class="mb-3">
                            <label class="form-label">Vehicle Type</label>
                            <select class="form-select" id="vehicle_type" onchange="SettingsRenderer.updateVehicleFields()">
                                <option value="boat" ${vehicleType === 'boat' ? 'selected' : ''}>🚢 Boat</option>
                                <option value="car" ${vehicleType === 'car' ? 'selected' : ''}>🚗 Car</option>
                                <option value="person" ${vehicleType === 'person' ? 'selected' : ''}>🚶 Person</option>
                                <option value="other" ${vehicleType === 'other' ? 'selected' : ''}>📦 Other</option>
                            </select>
                        </div>
                        <div class="mb-3">
                            <label class="form-label">Name</label>
                            <input type="text" class="form-control" id="vehicle_name" 
                                value="${this.val('vehicle_name', '')}" placeholder="e.g. My Boat, Station Alpha">
                        </div>
                        <div class="mb-3" id="mmsi_field" style="display: ${isBoat ? 'block' : 'none'}">
                            <label class="form-label">MMSI</label>
                            <input type="text" class="form-control" id="vessel_mmsi" 
                                value="${this.val('vessel_mmsi', '')}" placeholder="e.g. 211234567" maxlength="9">
                            <div class="form-text">Maritime Mobile Service Identity (9 digits)</div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    },

    renderAppPane: function () {
        return `
            <h5><i class="bi bi-gear-wide-connected"></i> App Settings</h5>
            
            <!-- Auto-Start -->
            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-power"></i> Auto-Start</div>
                <div class="card-body">
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="auto_start_boot" ${this.bool('auto_start_boot') ? 'checked' : ''}>
                        <label class="form-check-label" for="auto_start_boot">Start on device boot</label>
                    </div>
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="auto_start_usb" ${this.bool('auto_start_usb') ? 'checked' : ''}>
                        <label class="form-check-label" for="auto_start_usb">Start when USB device connected</label>
                    </div>
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="auto_start_launch" ${this.bool('auto_start_launch') ? 'checked' : ''}>
                        <label class="form-check-label" for="auto_start_launch">Start when app opens</label>
                    </div>
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="web_fallback_mode" ${this.bool('web_fallback_mode') ? 'checked' : ''}>
                        <label class="form-check-label" for="web_fallback_mode">Web-only mode if no USB device</label>
                    </div>
                </div>
            </div>
            
            <!-- Web Authentication -->
            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-shield-lock"></i> Web Authentication</div>
                <div class="card-body">
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="web_auth_enabled" ${this.bool('web_auth_enabled', true) ? 'checked' : ''}>
                        <label class="form-check-label" for="web_auth_enabled">Require password for web access</label>
                        <div class="form-text">On by default. Anyone on this network can otherwise read the settings and the log, credentials included.</div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Username</label>
                        <input type="text" class="form-control" id="web_auth_username" value="${this.val('web_auth_username', 'admin')}">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Password</label>
                        <div class="input-group">
                            <input type="password" class="form-control" id="web_auth_password" value="${this.val('web_auth_password', 'admin')}">
                            <button class="btn btn-outline-secondary" type="button"
                                onclick="const i=document.getElementById('web_auth_password'); i.type = i.type==='password' ? 'text' : 'password'">
                                <i class="bi bi-eye"></i>
                            </button>
                        </div>
                        <div class="form-text">Ships as <code>admin</code> / <code>admin</code>. Change it if the station shares a network with anyone else.</div>
                    </div>
                    <div class="alert alert-warning mb-0">
                        ⚠️ Changing authentication settings will take effect after service restart.
                    </div>
                </div>
            </div>
            
            <!-- DNS -->
            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-globe"></i> DNS</div>
                <div class="card-body">
                    <div class="mb-3 form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="dns_manual" ${this.bool('dns_manual') ? 'checked' : ''}>
                        <label class="form-check-label" for="dns_manual">Use custom DNS servers</label>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Primary DNS</label>
                        <input type="text" class="form-control" id="dns_primary" value="${this.val('dns_primary', '8.8.8.8')}">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Secondary DNS</label>
                        <input type="text" class="form-control" id="dns_secondary" value="${this.val('dns_secondary', '8.8.4.4')}">
                    </div>
                </div>
            </div>
            
            <!-- Battery -->
            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-battery-charging"></i> Battery</div>
                <div class="card-body">
                    <div class="alert alert-info mb-0">
                        To prevent Android from stopping the AIS service, disable battery optimization for this app in Android Settings → Apps → PortTracker AIS → Battery → Unrestricted.
                    </div>
                </div>
            </div>
        `;
    },

    /**
     * Where to go online once this station belongs to an account.
     *
     * The station keeps working on its own, but everything that needs more
     * than one station - how far it reaches, what it heard, the fleet around
     * it - lives on the web. Opening these from here saves people hunting for
     * the right page; links leave the app in a Chrome Custom Tab (see
     * SettingsActivity.shouldOverrideUrlLoading).
     */
    renderAccountLinks: function (stationId, accountEmail) {
        const PT = 'https://www.porttracker.co';
        const TD = 'https://www.trusteddocks.com';
        const links = [
            {
                href: `${PT}/antenna/${stationId}`,
                icon: 'bi-graph-up',
                title: 'This station&apos;s performance',
                detail: 'Reception per hour, vessels heard, how far it reaches',
            },
            {
                href: `${TD}/account/ais-stations/${stationId}`,
                icon: 'bi-clipboard-data',
                title: 'The same on TrustedDocks',
                detail: 'One login covers both - use whichever you work in',
            },
            {
                href: `${PT}/profile?tab=my-antennas-data`,
                icon: 'bi-broadcast-pin',
                title: 'All your antennas',
                detail: 'Every station on the account, and their status',
            },
            {
                href: `${PT}/dashboard`,
                icon: 'bi-speedometer2',
                title: 'Dashboard',
                detail: 'Ports, companies, watchlist and antennas at a glance',
            },
            {
                href: `${PT}/live`,
                icon: 'bi-map',
                title: 'Live map',
                detail: 'The traffic your station is helping to track',
            },
            {
                href: `${PT}/profile?tab=watchlist`,
                icon: 'bi-star',
                title: 'Watchlist',
                detail: 'Vessels you follow, and the reports they email you',
            },
        ];
        const rows = links.map(link => `
            <a class="list-group-item list-group-item-action d-flex align-items-start gap-3"
               href="${link.href}" target="_blank" rel="noopener">
                <i class="bi ${link.icon} mt-1"></i>
                <span>
                    <span class="fw-semibold">${link.title}</span><br>
                    <span class="text-muted" style="font-size:0.85em">${link.detail}</span>
                </span>
                <i class="bi bi-box-arrow-up-right ms-auto mt-1 text-muted"></i>
            </a>`).join('');
        return `
            <div class="card mb-3">
                <div class="card-header d-flex align-items-center gap-2">
                    <i class="bi bi-person-check"></i>
                    <span>Signed in${accountEmail ? ` as ${accountEmail}` : ''} — station #${stationId}</span>
                </div>
                <div class="list-group list-group-flush">${rows}</div>
            </div>`;
    },

    renderPorttrackerPane: function () {
        const mqttEnabled = this.bool('mqtt_enabled');
        const mqttGpsEnabled = this.bool('mqtt_gps_enabled');
        const displayName = this.val('station_display_name', '');
        const stationKind = this.val('station_kind', 'fixed');
        const stationPublic = this.bool('station_public');
        const brokerUrl = this.val('mqtt_broker_url', 'ssl://mqtt.navisense.de:8883');
        const mqttUsername = this.val('mqtt_username', '');
        const mqttPassword = this.val('mqtt_password', '');
        const antennaUuid = this.val('mqtt_antenna_uuid', '');
        const mqttTopicRaw = this.val('mqtt_topic_raw', '');
        const mqttTopicJson = this.val('mqtt_topic_json', '');
        const mqttFormat = this.val('mqtt_format', 'aisc-json');
        const topicsAssigned = mqttTopicRaw || mqttTopicJson;
        const stationId = parseInt(this.val('sharing_station_id', '0'), 10) || 0;
        const accountEmail = this.val('sharing_account_email', '');

        return `
            <h5><i class="bi bi-cloud-arrow-up"></i> PortTracker / TrustedDocks Account</h5>

            ${stationId > 0 ? this.renderAccountLinks(stationId, accountEmail) : ''}

            <!-- Sign in with the PortTracker / TrustedDocks account -->
            <div class="card mb-3 border-primary">
                <div class="card-body">
                    <h6 class="card-title"><i class="bi bi-person-check"></i> Sign in &amp; Auto-Provision <span class="badge bg-secondary">optional</span></h6>
                    <p class="text-muted" style="font-size:0.85em">
                        <strong>No account needed:</strong> the receiver, local dashboard,
                        map, database and UDP/TCP outputs all work standalone. Sign in only
                        if you want to share your AIS data and see your station's
                        statistics online. One login works for both
                        <strong>PortTracker</strong> (porttracker.co) and
                        <strong>TrustedDocks</strong> (trusteddocks.com) — use whichever
                        account you already have, or create one for free during sign-in.
                        Your data-sharing station is set up automatically — no keys to
                        copy. One account can run any number of stations; every device
                        gets its own.
                    </p>
                    <button id="auth-login-btn" class="btn btn-primary" onclick="SettingsRenderer.startAuthLogin()">
                        <i class="bi bi-box-arrow-in-right"></i> Sign in with PortTracker / TrustedDocks
                    </button>
                    <button id="auth-cancel-btn" class="btn btn-outline-secondary ms-2" style="display:none"
                        onclick="SettingsRenderer.cancelAuthLogin()">Cancel</button>
                    <div id="auth-login-status" class="mt-3"></div>
                </div>
            </div>

            <!-- Quick Setup via Paste Config -->
            <div class="card mb-3">
                <div class="card-body">
                    <h6 class="card-title"><i class="bi bi-clipboard-plus"></i> Paste Station Config</h6>
                    <p class="text-muted" style="font-size:0.85em">Copy the config from your <a href="https://www.trusteddocks.com/account/ais-stations" target="_blank">TrustedDocks account</a> and paste it below:</p>
                    <textarea id="station-config-paste" class="form-control font-monospace" rows="8" placeholder="[TrustedDocks Station]\nstation_name = ...\nbroker = ...\nusername = ...\npassword = ..."></textarea>
                    <button class="btn btn-primary mt-2" onclick="SettingsRenderer.applyPastedConfig()">
                        <i class="bi bi-check-lg"></i> Apply Config
                    </button>
                    <div id="paste-config-status" class="mt-2"></div>
                </div>
            </div>
            
            <p class="text-muted small">Or enter credentials manually below.</p>
            
            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-key"></i> MQTT Credentials</div>
                <div class="card-body">
                    <div class="mb-3">
                        <label class="form-label">Antenna UUID</label>
                        <input type="text" class="form-control font-monospace" id="mqtt_antenna_uuid" 
                            value="${antennaUuid}" placeholder="e.g. 54BBB8">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Protocol / Broker URL</label>
                        <input type="text" class="form-control font-monospace" id="mqtt_broker_url" 
                            value="${brokerUrl}" placeholder="ssl://mqtt.navisense.de:8883">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Username</label>
                        <input type="text" class="form-control font-monospace" id="mqtt_username" 
                            value="${mqttUsername}" placeholder="e.g. data-sharing-user-2539895">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Password</label>
                        <div class="input-group">
                            <input type="password" class="form-control font-monospace" id="mqtt_password" 
                                value="${mqttPassword}" placeholder="Your MQTT password">
                            <button class="btn btn-outline-secondary" type="button" 
                                onclick="const i=document.getElementById('mqtt_password'); i.type = i.type==='password' ? 'text' : 'password'">
                                <i class="bi bi-eye"></i>
                            </button>
                        </div>
                        <div class="form-text text-warning"><i class="bi bi-exclamation-triangle"></i> Password is shown only once at station creation. Save it!</div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">MQTT Topics</label>
                        ${topicsAssigned ? `
                        <div class="alert alert-info py-2 mb-0" style="font-size:0.85em">
                            <i class="bi bi-lock"></i> <strong>Auto-assigned</strong> (based on your user ID and antenna ID)<br>
                            <code class="text-dark">${mqttTopicJson || mqttTopicRaw}</code>
                        </div>
                        ` : `
                        <div class="alert alert-warning py-2 mb-0" style="font-size:0.85em">
                            <i class="bi bi-exclamation-triangle"></i> Topics not yet assigned. Connect via the <strong>Gateway API Key</strong> or use <strong>Paste Config</strong> above to provision.
                        </div>
                        `}
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Publish Format</label>
                        <select class="form-select" id="mqtt_format">
                            <option value="aisc-json" ${mqttFormat === 'aisc-json' ? 'selected' : ''}>AIS JSON (aisc-json) — recommended</option>
                            <option value="raw" ${mqttFormat === 'raw' ? 'selected' : ''}>Raw NMEA</option>
                        </select>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Station Name</label>
                        <input type="text" class="form-control" value="${this.val('pref_station_name', '')}" disabled
                            placeholder="Set in Networking → Station Name">
                        <div class="form-text">Uses the station name from Networking settings. UUID <code>${antennaUuid || '—'}</code> is the unique station identifier.</div>
                    </div>
                </div>
            </div>
            
            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-info-circle"></i> Connection Status</div>
                <div class="card-body">
                    <table class="table table-bordered mb-0">
                        <tr>
                            <th style="width:40%">MQTT Status</th>
                            <td id="gateway_mqtt_status">
                                <span class="text-muted">—</span>
                            </td>
                        </tr>
                        <tr>
                            <th>Messages Sent</th>
                            <td><span class="badge bg-info fs-6" id="gateway_msg_count">0</span></td>
                        </tr>
                    </table>
                </div>
            </div>
            
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="mqtt_enabled" ${mqttEnabled ? 'checked' : ''}>
                <label class="form-check-label" for="mqtt_enabled"><strong>Enable MQTT Publishing</strong></label>
            </div>

            <div class="card mb-3">
                <div class="card-header"><i class="bi bi-signpost-2"></i> How this station appears</div>
                <div class="card-body">
                    <div class="mb-3">
                        <label class="form-label">Display name</label>
                        <input type="text" class="form-control" id="station_display_name"
                            value="${displayName}" maxlength="80"
                            placeholder="e.g. MS Hamburg, Service Van 3, Pier 4 roof">
                        <div class="form-text">
                            The real name of whatever carries this antenna. Used
                            wherever the station is shown instead of its technical
                            hostname.
                        </div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Mounted on</label>
                        <select class="form-select" id="station_kind">
                            <option value="fixed" ${stationKind === 'fixed' ? 'selected' : ''}>A fixed position (mast, roof, pier)</option>
                            <option value="vessel" ${stationKind === 'vessel' ? 'selected' : ''}>A vessel or floating platform</option>
                            <option value="vehicle" ${stationKind === 'vehicle' ? 'selected' : ''}>A vehicle</option>
                            <option value="person" ${stationKind === 'person' ? 'selected' : ''}>A person on the move</option>
                        </select>
                        <div class="form-text">
                            Anything but a fixed position is treated as moving: its
                            position is followed rather than set once, and coverage
                            figures are not measured against a single spot.
                        </div>
                    </div>
                    <div class="form-check form-switch">
                        <input class="form-check-input" type="checkbox" id="station_public"
                            ${stationPublic ? 'checked' : ''}>
                        <label class="form-check-label" for="station_public">
                            <strong>Show this station publicly on porttracker.co</strong>
                        </label>
                        <div class="form-text">
                            Off by default. When on, the display name, the type and
                            the position above are visible to other PortTracker users
                            — a moving station appears on the map. Turn it off and the
                            station is yours alone again.
                        </div>
                    </div>
                </div>
            </div>

            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="mqtt_gps_enabled" ${mqttGpsEnabled ? 'checked' : ''}>
                <label class="form-check-label" for="mqtt_gps_enabled"><strong>Send this station's position</strong></label>
                <div class="form-text">
                    Reports the device's own GPS position with the station status,
                    so the antenna sits in the right place on the map and its
                    coverage can be measured — how far it hears, and in which
                    direction, is worked out from where it stands. Nothing about
                    the position is published to other users beyond what your
                    station page already shows. Leave it off for a station whose
                    location you would rather set by hand on the website.
                </div>
            </div>
            
            <button class="btn btn-outline-secondary btn-sm" onclick="SettingsRenderer.refreshGatewayStatus()">
                <i class="bi bi-arrow-clockwise"></i> Refresh Status
            </button>
        `;
    },

    renderDatabasePane: function () {
        return `
            <h5><i class="bi bi-database"></i> Internal Database</h5>
            <p class="text-muted small">Store received AIS data locally on the device for later analysis.</p>
            
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="internal_db_enabled" ${this.bool('internal_db_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="internal_db_enabled"><strong>Write to Internal Database</strong></label>
            </div>
            <div class="alert alert-secondary">
                <i class="bi bi-info-circle"></i> When enabled, static vessel data (name, IMO, callsign, type, dimensions) is stored in a local SQLite database for fast identification and export.
            </div>

            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="position_logging_enabled" ${this.bool('position_logging_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="position_logging_enabled"><strong>Log vessel positions (track history)</strong></label>
            </div>
            <div class="alert alert-warning">
                <i class="bi bi-exclamation-triangle"></i> Stores each vessel's position/speed/course over time (movement-based, kept 3 months then downsampled). Uses significantly more storage — leave off unless you need track history. Requires the internal database to be enabled.
            </div>
        `;
    },

    renderControlPane: function () {
        return `
            <div class="d-grid gap-2">
                <button class="btn btn-warning" onclick="SettingsRenderer.restartService()">
                    <i class="bi bi-arrow-clockwise"></i> Restart Service Only
                </button>
                 <div class="alert alert-info mt-2">
                    <b>Note:</b> Saving settings will automatically restart the service. Use this button only if you want to force a restart without saving.
                </div>
            </div>

            ${this.bool('sideload_updates_available', false) ? `
            <!-- OTA Update Section -->
            <div class="card mt-4">
                <div class="card-header"><i class="bi bi-cloud-download"></i> Software Update</div>
                <div class="card-body">
                    <div id="update-status" class="mb-3">
                        <span class="text-muted">Click "Check for Updates" to see if a newer version is available.</span>
                    </div>
                    <div class="d-flex gap-2">
                        <button id="btn-update-check" class="btn btn-outline-primary" onclick="SettingsRenderer.checkForUpdate()">
                            <i class="bi bi-arrow-repeat"></i> Check for Updates
                        </button>
                        <button id="btn-update-install" class="btn btn-success d-none" onclick="SettingsRenderer.installUpdate()">
                            <i class="bi bi-download"></i> Download & Install
                        </button>
                    </div>
                    <div id="update-progress" class="mt-3 d-none">
                        <div class="progress" style="height: 24px;">
                            <div id="update-progress-bar" class="progress-bar progress-bar-striped progress-bar-animated" style="width: 0%">0%</div>
                        </div>
                        <small class="text-muted mt-1 d-block">Downloading APK... The install prompt will appear on the device screen.</small>
                    </div>
                </div>
            </div>
            ` : '<div class="alert alert-info mt-4">App updates are managed by Google Play.</div>'}
        `;
    },

    // --- Data Helpers ---

    val: function (key, defaultVal = '') {
        return this.currentConfig[key] !== undefined ? this.currentConfig[key] : defaultVal;
    },

    bool: function (key, defaultVal = false) {
        if (this.currentConfig[key] === undefined) return defaultVal;
        return this.currentConfig[key] === true || this.currentConfig[key] === 'true';
    },

    getDeviceTypeName: function (val) {
        const types = { '0': 'RTL-TCP', '1': 'RTL-SDR', '2': 'AirSpy', '3': 'AirSpy HF+', '4': 'SpyServer' };
        return types[val] || 'Unknown';
    },

    // --- Actions ---

    save: async function () {
        const newConfig = { ...this.currentConfig };

        // Helper to collect values
        const collect = (id, type = 'string') => {
            const el = document.getElementById(id);
            if (!el) return;
            if (el.type === 'checkbox') {
                newConfig[id] = el.checked;
            } else {
                newConfig[id] = type === 'int' ? parseInt(el.value, 10) : el.value;
            }
        };

        // Collect Networking
        collect('pref_enable_remote', 'bool');
        collect('pref_station_name');
        collect('pref_local_web_port', 'int');
        collect('webviewer_enabled', 'bool');

        // Collect Settings
        collect('device_type', 'string'); // Saved as string "1" usually
        collect('frequency_correction', 'int');
        collect('tcp_enabled', 'bool');
        collect('tcp_port', 'int');
        collect('gpsd_enabled', 'bool');
        collect('gpsd_host');
        collect('gpsd_port', 'int');
        collect('gpsd_interval');

        // Chartplotter NMEA network output
        collect('nmea_out_enabled', 'bool');
        collect('nmea_out_format');
        collect('nmea_out_tcp_enabled', 'bool');
        collect('nmea_out_tcp_port');
        collect('nmea_out_udp_enabled', 'bool');
        collect('nmea_out_udp_host');
        collect('nmea_out_udp_port');
        collect('nmea_out_gps', 'bool');

        for (let i = 1; i <= 4; i++) {
            collect(`udp${i}_enabled`, 'bool');
            collect(`udp${i}_host`);
            collect(`udp${i}_port`, 'int');
            collect(`udp${i}_json`, 'bool');
        }

        // Collect TrustedDocks / MQTT settings
        collect('mqtt_enabled', 'bool');
        collect('mqtt_gps_enabled', 'bool');
        collect('station_display_name');
        collect('station_kind');
        collect('station_public', 'bool');
        collect('mqtt_broker_url');
        collect('mqtt_username');
        collect('mqtt_password');
        collect('mqtt_antenna_uuid');
        collect('mqtt_format');
        // Topics are auto-constructed (userId/antennaId) — not user-editable

        // Collect Internal DB settings
        collect('internal_db_enabled', 'bool');
        collect('position_logging_enabled', 'bool');

        // Collect App settings
        collect('auto_start_boot', 'bool');
        collect('auto_start_usb', 'bool');
        collect('auto_start_launch', 'bool');
        collect('web_fallback_mode', 'bool');
        collect('web_auth_enabled', 'bool');
        collect('web_auth_username');
        collect('web_auth_password');
        collect('dns_manual', 'bool');
        collect('dns_primary');
        collect('dns_secondary');

        // Collect Location settings
        const installationType = document.getElementById('installation_fixed')?.checked ? 'fixed' : 'moving';
        newConfig['installation_type'] = installationType;
        collect('fixed_latitude');
        collect('fixed_longitude');
        collect('vehicle_type');
        collect('vehicle_name');
        collect('vessel_mmsi');

        // Send to API
        this.showLoading("Saving configuration...");

        try {
            const response = await fetch('/admin/api/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'postData=' + encodeURIComponent(JSON.stringify(newConfig))
            });

            if (response.ok) {
                this.hideLoading();
                alert("✅ Settings saved and applied! MQTT and FRP connections have been updated.");
                // Reload settings from server after a short delay to let changes apply
                setTimeout(() => { this.init(); }, 1500);
            } else {
                throw new Error("Save failed (HTTP " + response.status + ")");
            }
        } catch (e) {
            this.hideLoading();
            // Don't show error for network issues during reload — settings were likely saved
            if (e.message === "Failed to fetch") {
                alert("⚠️ Settings saved, but the connection was briefly interrupted. Please refresh the page.");
            } else {
                alert("Error saving settings: " + e.message);
            }
        }
    },

    restartService: async function () {
        if (!confirm("To apply settings changes, please close and reopen the app. Continue?")) return;

        try {
            await fetch('/admin/restart', { method: 'POST' });
        } catch (e) { /* ignore */ }
        alert("Please close and reopen the app to apply changes.");
    },

    refreshGatewayStatus: async function () {
        try {
            const response = await fetch('/admin/api/gateway/status');
            const status = await response.json();

            const mqttStatusEl = document.getElementById('gateway_mqtt_status');
            if (mqttStatusEl) {
                if (status.mqtt_connected) {
                    mqttStatusEl.innerHTML = '<span class="text-success">🟢 Connected</span>';
                } else if (status.mqtt_enabled) {
                    mqttStatusEl.innerHTML = '<span class="text-warning">🟡 Enabled (reconnecting...)</span>';
                } else {
                    mqttStatusEl.innerHTML = '<span class="text-secondary">⚪ Disabled</span>';
                }
            }

            const msgEl = document.getElementById('gateway_msg_count');
            if (msgEl) msgEl.textContent = status.mqtt_messages_sent || 0;

        } catch (e) {
            console.error('Failed to refresh gateway status:', e);
        }
    },

    _authPollTimer: null,

    startAuthLogin: async function () {
        const statusEl = document.getElementById('auth-login-status');
        const loginBtn = document.getElementById('auth-login-btn');
        const cancelBtn = document.getElementById('auth-cancel-btn');
        try {
            loginBtn.disabled = true;
            const response = await fetch('/admin/api/auth/start', { method: 'POST' });
            const s = await response.json();
            this._renderAuthStatus(s);
            if (s.state === 'pending') {
                cancelBtn.style.display = '';
                if (this._authPollTimer) clearInterval(this._authPollTimer);
                this._authPollTimer = setInterval(() => this.pollAuthStatus(), 3000);
            } else {
                loginBtn.disabled = false;
            }
        } catch (e) {
            loginBtn.disabled = false;
            if (statusEl) statusEl.innerHTML =
                '<div class="alert alert-danger py-2">' + e.message + '</div>';
        }
    },

    /** Re-attach to an in-flight device-code login after a page (re)load. */
    resumeAuthStatus: async function () {
        try {
            const response = await fetch('/admin/api/auth/status');
            const s = await response.json();
            if (!s.state || s.state === 'idle') return;
            this._renderAuthStatus(s);
            if (s.state === 'pending' || s.state === 'provisioning') {
                const loginBtn = document.getElementById('auth-login-btn');
                const cancelBtn = document.getElementById('auth-cancel-btn');
                if (loginBtn) loginBtn.disabled = true;
                if (cancelBtn) cancelBtn.style.display = '';
                if (this._authPollTimer) clearInterval(this._authPollTimer);
                this._authPollTimer = setInterval(() => this.pollAuthStatus(), 3000);
            }
        } catch (e) { /* best-effort */ }
    },

    pollAuthStatus: async function () {
        try {
            const response = await fetch('/admin/api/auth/status');
            const s = await response.json();
            this._renderAuthStatus(s);
            if (s.state === 'provisioned' || s.state === 'error' || s.state === 'idle') {
                clearInterval(this._authPollTimer);
                this._authPollTimer = null;
                document.getElementById('auth-login-btn').disabled = false;
                document.getElementById('auth-cancel-btn').style.display = 'none';
                if (s.state === 'provisioned') {
                    // Show the freshly persisted credentials/topics + MQTT state.
                    setTimeout(() => { this.refreshGatewayStatus(); }, 2500);
                }
            }
        } catch (e) {
            console.error('auth status poll failed', e);
        }
    },

    cancelAuthLogin: async function () {
        try { await fetch('/admin/api/auth/cancel', { method: 'POST' }); } catch (e) { }
        if (this._authPollTimer) { clearInterval(this._authPollTimer); this._authPollTimer = null; }
        document.getElementById('auth-login-btn').disabled = false;
        document.getElementById('auth-cancel-btn').style.display = 'none';
        const statusEl = document.getElementById('auth-login-status');
        if (statusEl) statusEl.innerHTML = '';
    },

    _renderAuthStatus: function (s) {
        const el = document.getElementById('auth-login-status');
        if (!el) return;
        if (s.state === 'pending') {
            el.innerHTML =
                '<div class="alert alert-info py-2">' +
                '<div><a href="' + s.verification_uri + '" target="_blank" rel="noopener" class="alert-link">' +
                '<i class="bi bi-box-arrow-up-right"></i> Tap here to sign in</a> with your ' +
                'PortTracker or TrustedDocks account. The sign-in page opens right here ' +
                'in the app — check that it shows this code:</div>' +
                '<div class="fs-3 fw-bold font-monospace text-center my-2">' + s.user_code + '</div>' +
                '<div class="text-muted" style="font-size:0.85em">Tip: you can also open the ' +
                'link on another device (e.g. your laptop) and enter the code there. ' +
                'Waiting for you to sign in…</div>' +
                '</div>';
        } else if (s.state === 'provisioning') {
            el.innerHTML = '<div class="alert alert-info py-2">Signed in — provisioning your station…</div>';
        } else if (s.state === 'provisioned') {
            el.innerHTML =
                '<div class="alert alert-success py-2"><i class="bi bi-check-circle"></i> ' +
                (s.message || ('Station #' + s.station_id + ' connected.')) +
                ' Data publishing starts automatically.</div>';
        } else if (s.state === 'error') {
            el.innerHTML = '<div class="alert alert-danger py-2">' + (s.message || 'Login failed.') + '</div>';
        } else {
            el.innerHTML = '';
        }
    },

    toggleService: async function (enable) {
        const endpoint = enable ? '/admin/api/service/start' : '/admin/api/service/stop';
        const statusEl = document.getElementById('sdr_service_status');

        try {
            const response = await fetch(endpoint, { method: 'POST' });
            const result = await response.json();

            if (result.success) {
                if (statusEl) {
                    statusEl.className = enable ? 'text-success' : 'text-secondary';
                    statusEl.textContent = enable ? '🟢 Running' : '⚪ Stopped';
                }
                // Refresh after a short delay to get updated status
                setTimeout(() => this.refreshSdrStatus(), 2000);
            } else {
                throw new Error(result.error || 'Failed to toggle service');
            }
        } catch (e) {
            alert('Error: ' + e.message);
            // Revert checkbox
            document.getElementById('service_enabled').checked = !enable;
        }
    },

    requestUsbPermission: async function () {
        try {
            // Try native bridge first (works when in app WebView)
            if (typeof Android !== 'undefined' && Android.requestUsbPermission) {
                Android.requestUsbPermission();
                setTimeout(() => this.refreshSdrStatus(), 3000);
                return;
            }
            // Fallback: use API endpoint (works from external browser)
            const response = await fetch('/admin/api/usb/request_permission', { method: 'POST' });
            const result = await response.json();
            if (result.success) {
                alert('✅ ' + result.message);
            } else {
                alert('⚠️ ' + (result.message || result.error || 'Failed'));
            }
            setTimeout(() => this.refreshSdrStatus(), 3000);
        } catch (e) {
            alert('Error: ' + e.message);
        }
    },

    restartEngine: async function () {
        if (!confirm('Restart the SDR engine? This will re-scan USB devices and reconnect.')) return;
        try {
            const response = await fetch('/admin/api/engine/restart', { method: 'POST' });
            const result = await response.json();
            if (result.success) {
                alert('✅ Engine restart triggered. Please wait...');
            } else {
                alert('⚠️ ' + (result.error || 'Failed to restart'));
            }
            setTimeout(() => this.refreshSdrStatus(), 5000);
        } catch (e) {
            alert('Error: ' + e.message);
        }
    },

    refreshSdrStatus: async function () {
        try {
            const response = await fetch('/admin/api/status');
            const status = await response.json();

            // Update UI elements
            const serviceStatus = document.getElementById('sdr_service_status');
            const msgCount = document.getElementById('sdr_msg_count');
            const serviceCheckbox = document.getElementById('service_enabled');

            if (status.service_running !== undefined) {
                if (serviceStatus) {
                    serviceStatus.className = status.service_running ? 'text-success' : 'text-secondary';
                    serviceStatus.textContent = status.service_running ? '🟢 Running' : '⚪ Stopped';
                }
                if (serviceCheckbox) {
                    serviceCheckbox.checked = status.service_running;
                }
            }

            if (status.message_count !== undefined && msgCount) {
                msgCount.textContent = status.message_count;
            }

            // Update device info
            const deviceName = document.getElementById('sdr_device_name');
            if (deviceName) {
                deviceName.innerHTML = '<strong>' + (status.sdr_device_name || 'No device') + '</strong>';
            }

            const sdrConnected = document.getElementById('sdr_connected');
            if (sdrConnected) {
                const connected = status.sdr_connected === true;
                sdrConnected.innerHTML = connected
                    ? '<span class="text-success">✅ Connected</span>'
                    : '<span class="text-danger">❌ Not Connected</span>';
            }

            const usbPermission = document.getElementById('sdr_usb_permission');
            if (usbPermission) {
                const granted = status.sdr_permission === true;
                usbPermission.innerHTML = granted
                    ? '<span class="text-success">✅ Granted</span>'
                    : '<span class="text-warning">⚠️ Not Granted</span>';
            }

            // Update USB device list
            const usbDeviceList = document.getElementById('usb_device_list');
            if (usbDeviceList && Array.isArray(status.usb_devices)) {
                if (status.usb_devices.length > 0) {
                    usbDeviceList.innerHTML = status.usb_devices.map(dev => `
                        <tr>
                            <td>${dev.name || 'Unknown'}</td>
                            <td><code>${dev.vendorId || '?'}:${dev.productId || '?'}</code></td>
                            <td>${dev.isSDR ? '<span class="badge bg-primary">SDR</span>' : ''}${dev.hasPermission ? ' <span class="badge bg-success">Permitted</span>' : ''}</td>
                        </tr>
                    `).join('');
                } else {
                    usbDeviceList.innerHTML = '<tr><td colspan="3" class="text-muted">No USB devices detected</td></tr>';
                }
            }

            // Update config for full re-render if needed
            this.currentConfig = { ...this.currentConfig, ...status };

        } catch (e) {
            console.error('Failed to refresh SDR status:', e);
        }
    },

    // Auto-refresh SDR status when tab is visible
    _sdrRefreshInterval: null,

    startSdrAutoRefresh: function () {
        this.stopSdrAutoRefresh();
        this.refreshSdrStatus(); // Immediate refresh
        this._sdrRefreshInterval = setInterval(() => this.refreshSdrStatus(), 5000);
    },

    stopSdrAutoRefresh: function () {
        if (this._sdrRefreshInterval) {
            clearInterval(this._sdrRefreshInterval);
            this._sdrRefreshInterval = null;
        }
    },

    showLoading: function (msg) {
        // Simple overlay
        const overlay = document.createElement('div');
        overlay.id = 'settings-overlay';
        overlay.style = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.7);z-index:9999;display:flex;justify-content:center;align-items:center;color:white;flex-direction:column';
        overlay.innerHTML = `<div class="spinner-border" role="status"></div><h4 class="mt-3">${msg}</h4>`;
        document.body.appendChild(overlay);
    },

    hideLoading: function () {
        const overlay = document.getElementById('settings-overlay');
        if (overlay) overlay.remove();
    },

    // Location tab helper functions
    updateLocationFields: function () {
        const isFixed = document.getElementById('installation_fixed')?.checked;
        const fixedFields = document.getElementById('fixed_installation_fields');
        const movingFields = document.getElementById('moving_installation_fields');

        if (fixedFields) fixedFields.style.display = isFixed ? 'block' : 'none';
        if (movingFields) movingFields.style.display = isFixed ? 'none' : 'block';
    },

    updateVehicleFields: function () {
        const vehicleType = document.getElementById('vehicle_type')?.value;
        const mmsiField = document.getElementById('mmsi_field');

        if (mmsiField) {
            mmsiField.style.display = vehicleType === 'boat' ? 'block' : 'none';
        }
    },

    openMapPicker: function () {
        const lat = parseFloat(document.getElementById('fixed_latitude')?.value) || 53.5511;
        const lon = parseFloat(document.getElementById('fixed_longitude')?.value) || 9.9937;

        // Create modal with embedded map
        const modal = document.createElement('div');
        modal.id = 'map-picker-modal';
        modal.style = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.8);z-index:9999;display:flex;flex-direction:column;padding:20px;';
        modal.innerHTML = `
            <div style="background:white;border-radius:8px;flex:1;display:flex;flex-direction:column;overflow:hidden;">
                <div style="padding:15px;border-bottom:1px solid #ddd;display:flex;justify-content:space-between;align-items:center;">
                    <h5 style="margin:0;"><i class="bi bi-crosshair"></i> Pick Location</h5>
                    <button class="btn btn-close" onclick="document.getElementById('map-picker-modal').remove()"></button>
                </div>
                <div id="map-picker-container" style="flex:1;position:relative;">
                    <iframe id="map-picker-iframe" 
                        src="https://www.openstreetmap.org/export/embed.html?bbox=${lon - 0.1}%2C${lat - 0.05}%2C${lon + 0.1}%2C${lat + 0.05}&layer=mapnik&marker=${lat}%2C${lon}"
                        style="width:100%;height:100%;border:none;">
                    </iframe>
                    <div style="position:absolute;bottom:10px;left:10px;right:10px;background:white;padding:10px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,0.2);">
                        <div class="row g-2 align-items-center">
                            <div class="col-auto"><label>Lat:</label></div>
                            <div class="col"><input type="number" step="0.000001" class="form-control form-control-sm" id="picker_lat" value="${lat}"></div>
                            <div class="col-auto"><label>Lon:</label></div>
                            <div class="col"><input type="number" step="0.000001" class="form-control form-control-sm" id="picker_lon" value="${lon}"></div>
                            <div class="col-auto">
                                <button class="btn btn-primary btn-sm" onclick="SettingsRenderer.confirmMapLocation()">
                                    <i class="bi bi-check"></i> Confirm
                                </button>
                            </div>
                        </div>
                        <div class="form-text mt-1">Enter coordinates or use browser location button below</div>
                        <button class="btn btn-outline-secondary btn-sm mt-2" onclick="SettingsRenderer.useCurrentLocation()">
                            <i class="bi bi-geo-alt-fill"></i> Use My Current Location
                        </button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(modal);
    },

    confirmMapLocation: function () {
        const lat = document.getElementById('picker_lat')?.value;
        const lon = document.getElementById('picker_lon')?.value;

        if (lat && lon) {
            document.getElementById('fixed_latitude').value = lat;
            document.getElementById('fixed_longitude').value = lon;
        }

        document.getElementById('map-picker-modal')?.remove();
    },

    useCurrentLocation: function () {
        if (navigator.geolocation) {
            navigator.geolocation.getCurrentPosition(
                (position) => {
                    document.getElementById('picker_lat').value = position.coords.latitude.toFixed(6);
                    document.getElementById('picker_lon').value = position.coords.longitude.toFixed(6);
                },
                (error) => {
                    alert('Could not get location: ' + error.message);
                }
            );
        } else {
            alert('Geolocation is not supported by this browser');
        }
    },

    // --- Paste Config for TrustedDocks Provisioning ---

    applyPastedConfig: function () {
        const textarea = document.getElementById('station-config-paste');
        const statusDiv = document.getElementById('paste-config-status');
        if (!textarea || !statusDiv) return;

        const text = textarea.value.trim();
        if (!text) {
            statusDiv.innerHTML = '<span class="text-danger"><i class="bi bi-x-circle"></i> Please paste a config snippet first.</span>';
            return;
        }

        // Parse INI-like config
        const parsed = {};
        const lines = text.split('\n');
        for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed || trimmed.startsWith('[')) continue;
            const eqIdx = trimmed.indexOf('=');
            if (eqIdx === -1) continue;
            const key = trimmed.substring(0, eqIdx).trim();
            const value = trimmed.substring(eqIdx + 1).trim();
            parsed[key] = value;
        }

        // Map config keys to form field IDs
        const fieldMap = {
            'station_name': 'mqtt_station_name',
            'antenna_uuid': 'mqtt_antenna_uuid',
            'broker': 'mqtt_broker_url',
            'username': 'mqtt_username',
            'password': 'mqtt_password'
            // Topics are auto-constructed from userId/antennaId — not pasted
        };

        let filled = 0;
        for (const [configKey, fieldId] of Object.entries(fieldMap)) {
            if (parsed[configKey]) {
                const el = document.getElementById(fieldId);
                if (el) {
                    el.value = parsed[configKey];
                    el.style.borderColor = '#198754';
                    el.style.boxShadow = '0 0 0 0.2rem rgba(25,135,84,0.25)';
                    setTimeout(() => {
                        el.style.borderColor = '';
                        el.style.boxShadow = '';
                    }, 3000);
                    filled++;
                }
            }
        }

        if (filled > 0) {
            const name = parsed['station_name'] || 'Unknown';
            statusDiv.innerHTML = `
                <div class="alert alert-success">
                    <i class="bi bi-check-circle-fill"></i>
                    <strong>Station "${name}" configured!</strong><br>
                    ${filled} fields filled. Click <strong>Save & Restart Service</strong> to activate.
                </div>
            `;
        } else {
            statusDiv.innerHTML = '<span class="text-danger"><i class="bi bi-x-circle"></i> Could not parse any fields from the pasted config. Check the format.</span>';
        }
    },

    // --- OTA Update Functions ---

    checkForUpdate: async function () {
        const statusEl = document.getElementById('update-status');
        const checkBtn = document.getElementById('btn-update-check');
        const installBtn = document.getElementById('btn-update-install');

        checkBtn.disabled = true;
        checkBtn.innerHTML = '<i class="bi bi-hourglass-split"></i> Checking...';
        statusEl.innerHTML = '<span class="text-muted"><i class="bi bi-hourglass-split"></i> Checking GitHub releases...</span>';

        try {
            const resp = await fetch('/admin/api/update/check');
            const data = await resp.json();

            if (!data.success) {
                statusEl.innerHTML = `<div class="alert alert-danger py-2 mb-0"><i class="bi bi-exclamation-triangle"></i> ${data.error || 'Check failed'}</div>`;
                return;
            }

            const u = data.update;
            if (u.update_available) {
                statusEl.innerHTML = `
                    <div class="alert alert-warning py-2 mb-0">
                        <i class="bi bi-cloud-arrow-down"></i> <strong>Update available!</strong><br>
                        <small>Current: <code>${u.current_version_name}</code> (v${u.current_version_code}) → Latest: <code>${u.latest_version_name}</code> (v${u.latest_version_code})</small>
                        ${u.release_notes ? '<hr class="my-1"><small>' + u.release_notes.substring(0, 200) + '</small>' : ''}
                        ${u.apk_size_bytes ? '<br><small class="text-muted">APK size: ' + (u.apk_size_bytes / 1024 / 1024).toFixed(1) + ' MB</small>' : ''}
                    </div>`;
                installBtn.classList.remove('d-none');
            } else {
                statusEl.innerHTML = `
                    <div class="alert alert-success py-2 mb-0">
                        <i class="bi bi-check-circle"></i> <strong>Up to date!</strong><br>
                        <small>Current version: <code>${u.current_version_name}</code> (v${u.current_version_code})</small>
                    </div>`;
                installBtn.classList.add('d-none');
            }
        } catch (e) {
            statusEl.innerHTML = `<div class="alert alert-danger py-2 mb-0"><i class="bi bi-wifi-off"></i> Network error: ${e.message}</div>`;
        } finally {
            checkBtn.disabled = false;
            checkBtn.innerHTML = '<i class="bi bi-arrow-repeat"></i> Check for Updates';
        }
    },

    installUpdate: async function () {
        const installBtn = document.getElementById('btn-update-install');
        const progressDiv = document.getElementById('update-progress');
        const progressBar = document.getElementById('update-progress-bar');
        const statusEl = document.getElementById('update-status');

        installBtn.disabled = true;
        installBtn.innerHTML = '<i class="bi bi-hourglass-split"></i> Starting...';
        progressDiv.classList.remove('d-none');

        try {
            const resp = await fetch('/admin/api/update/install', { method: 'POST' });
            const data = await resp.json();

            if (!data.success) {
                statusEl.innerHTML = `<div class="alert alert-danger py-2 mb-0"><i class="bi bi-exclamation-triangle"></i> ${data.error}</div>`;
                progressDiv.classList.add('d-none');
                installBtn.disabled = false;
                installBtn.innerHTML = '<i class="bi bi-download"></i> Download & Install';
                return;
            }

            // Poll progress
            const pollInterval = setInterval(async () => {
                try {
                    const sr = await fetch('/admin/api/update/status');
                    const sd = await sr.json();
                    const pct = sd.download_progress || 0;
                    progressBar.style.width = pct + '%';
                    progressBar.textContent = pct + '%';

                    if (!sd.downloading && pct >= 100) {
                        clearInterval(pollInterval);
                        statusEl.innerHTML = `
                            <div class="alert alert-success py-2 mb-0">
                                <i class="bi bi-check-circle"></i> <strong>APK downloaded!</strong>
                                The install dialog should appear on the device screen. Tap "Install" to complete the update.
                            </div>`;
                        progressDiv.classList.add('d-none');
                        installBtn.classList.add('d-none');
                    } else if (!sd.downloading && sd.error) {
                        clearInterval(pollInterval);
                        statusEl.innerHTML = `<div class="alert alert-danger py-2 mb-0"><i class="bi bi-exclamation-triangle"></i> ${sd.error}</div>`;
                        progressDiv.classList.add('d-none');
                        installBtn.disabled = false;
                        installBtn.innerHTML = '<i class="bi bi-download"></i> Retry';
                    }
                } catch (e) {
                    // Network error during polling — might mean app is restarting
                    clearInterval(pollInterval);
                    statusEl.innerHTML = '<div class="alert alert-info py-2 mb-0"><i class="bi bi-arrow-clockwise"></i> App may be restarting for the update...</div>';
                }
            }, 1000);

        } catch (e) {
            statusEl.innerHTML = `<div class="alert alert-danger py-2 mb-0"><i class="bi bi-wifi-off"></i> ${e.message}</div>`;
            progressDiv.classList.add('d-none');
            installBtn.disabled = false;
            installBtn.innerHTML = '<i class="bi bi-download"></i> Download & Install';
        }
    }
};
