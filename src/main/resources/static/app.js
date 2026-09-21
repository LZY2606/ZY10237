let caseData = null;
let activeTab = 'fixes';
let selectedCandidate = 'CANDIDATE-A-ACCEL';

const svgNS = 'http://www.w3.org/2000/svg';
const map = document.getElementById('map');
const detailList = document.getElementById('detail-list');
const candidateSelect = document.getElementById('candidate-select');

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  if (!response.ok) {
    throw new Error(await response.text());
  }
  return response.json();
}

async function refresh() {
  caseData = await api('/api/case');
  selectedCandidate = caseData.retainedCandidates.includes(selectedCandidate)
    ? selectedCandidate
    : caseData.retainedCandidates[0];
  candidateSelect.value = selectedCandidate;
  render();
}

function render() {
  renderMeta();
  renderWarnings();
  renderMap();
  renderDetails();
  renderBadges();
}

function renderMeta() {
  const importTime = formatTime(caseData.importedAt);
  document.getElementById('case-meta').textContent =
    `动物 ${caseData.animalId} · 来源 ${caseData.source} · 导入 ${importTime}`;
}

function renderWarnings() {
  const box = document.getElementById('warning-list');
  box.replaceChildren(...caseData.warnings.map(text => {
    const item = document.createElement('div');
    item.className = 'warning';
    item.textContent = text;
    return item;
  }));
}

function renderBadges() {
  const box = document.getElementById('candidate-badges');
  box.replaceChildren();
  for (const candidate of ['CANDIDATE-A-ACCEL', 'CANDIDATE-B-SPATIAL']) {
    const badge = document.createElement('span');
    badge.className = 'badge' + (caseData.retainedCandidates.includes(candidate) ? ' kept' : '');
    badge.textContent = candidate === 'CANDIDATE-A-ACCEL' ? '保留：加速度候选' : '保留：空间候选';
    box.appendChild(badge);
  }
}

function project(x, y) {
  const width = 1000;
  const height = 700;
  const margin = 70;
  const minX = 80;
  const maxX = 900;
  const minY = 80;
  const maxY = 860;
  return {
    x: margin + ((x - minX) / (maxX - minX)) * (width - margin * 2),
    y: height - margin - ((y - minY) / (maxY - minY)) * (height - margin * 2)
  };
}

function svgElement(tag, attrs = {}, text) {
  const element = document.createElementNS(svgNS, tag);
  for (const [key, value] of Object.entries(attrs)) {
    element.setAttribute(key, value);
  }
  if (text !== undefined) {
    element.textContent = text;
  }
  return element;
}

function renderMap() {
  map.replaceChildren();
  for (let x = 100; x <= 900; x += 100) {
    const a = project(x, 80);
    const b = project(x, 860);
    map.appendChild(svgElement('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: 'grid-line' }));
    map.appendChild(svgElement('text', { x: a.x, y: 675, 'text-anchor': 'middle', class: 'axis-label' }, `${x}m`));
  }
  for (let y = 100; y <= 800; y += 100) {
    const a = project(80, y);
    const b = project(900, y);
    map.appendChild(svgElement('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: 'grid-line' }));
    map.appendChild(svgElement('text', { x: 35, y: a.y + 4, 'text-anchor': 'middle', class: 'axis-label' }, `${y}m`));
  }

  const fixById = new Map(caseData.fixes.map(fix => [fix.id, fix]));
  for (const edge of caseData.edges) {
    const from = project(fixById.get(edge.fromFixId).xM, fixById.get(edge.fromFixId).yM);
    const to = project(fixById.get(edge.toFixId).xM, fixById.get(edge.toFixId).yM);
    if (edge.drawPath) {
      const state = segmentStateForEdge(edge);
      map.appendChild(svgElement('line', {
        x1: from.x, y1: from.y, x2: to.x, y2: to.y,
        class: `path ${state.toLowerCase()}`
      }));
      const middle = { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 - 7 };
      map.appendChild(svgElement('text', {
        x: middle.x, y: middle.y, 'text-anchor': 'middle', class: 'speed-label'
      }, edge.speedMps === null ? '禁速' : `${edge.speedMps} m/s`));
    } else {
      const middle = { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 };
      map.appendChild(svgElement('rect', {
        x: middle.x - 11,
        y: middle.y - 11,
        width: 22,
        height: 22,
        rx: 5,
        fill: '#fff3f3',
        stroke: '#b54b4b',
        'stroke-width': 2
      }));
      map.appendChild(svgElement('line', {
        x1: middle.x - 5, y1: middle.y - 5, x2: middle.x + 5, y2: middle.y + 5, class: 'barrier'
      }));
      map.appendChild(svgElement('line', {
        x1: middle.x - 5, y1: middle.y + 5, x2: middle.x + 5, y2: middle.y - 5, class: 'barrier'
      }));
      map.appendChild(svgElement('text', {
        x: middle.x, y: middle.y - 8, 'text-anchor': 'middle', class: 'blocked-label'
      }, edge.barrierType || 'CRS BLOCK'));
    }
  }

  for (const fix of caseData.fixes) {
    renderEllipse(fix);
  }
  for (const fix of caseData.fixes) {
    renderFix(fix);
  }
}

function renderEllipse(fix) {
  const point = project(fix.xM, fix.yM);
  const p1 = project(fix.xM + fix.errorMajorM, fix.yM);
  const p2 = project(fix.xM, fix.yM + fix.errorMinorM);
  const radiusX = Math.max(4, Math.abs(p1.x - point.x));
  const radiusY = Math.max(4, Math.abs(p2.y - point.y));
  const bad = fix.errorCrs !== fix.coordCrs;
  map.appendChild(svgElement('ellipse', {
    cx: point.x,
    cy: point.y,
    rx: bad ? radiusX : radiusX,
    ry: radiusY,
    transform: `rotate(${-fix.errorOrientationDeg} ${point.x} ${point.y})`,
    class: `ellipse ${bad ? 'bad' : ''}`
  }));
}

function renderFix(fix) {
  const point = project(fix.xM, fix.yM);
  const circle = svgElement('circle', {
    cx: point.x,
    cy: point.y,
    r: fix.qualityStatus === 'REVIEW' ? 8 : 6,
    class: `fix ${fix.qualityStatus} ${fix.clockRollback ? 'clock' : ''}`
  });
  const title = svgElement('title', {}, `${fix.id} / ${fix.deviceId} / ${fix.recordedAt}`);
  circle.appendChild(title);
  map.appendChild(circle);
  map.appendChild(svgElement('text', {
    x: point.x + 9,
    y: point.y - 8,
    class: 'fix-label'
  }, fix.id));
  if (fix.returnToPreviousLocation) {
    map.appendChild(svgElement('text', {
      x: point.x, y: point.y - 20, 'text-anchor': 'middle', class: 'fix-label'
    }, '返回旧位置'));
  }
}

function segmentStateForEdge(edge) {
  const segment = caseData.segments.find(item =>
    item.candidate === selectedCandidate
      && item.type === 'BEHAVIOR'
      && item.fixIds.includes(edge.fromFixId)
      && item.fixIds.includes(edge.toFixId));
  return segment ? segment.state : 'UNCERTAIN';
}

function renderDetails() {
  detailList.replaceChildren();
  if (activeTab === 'fixes') {
    renderFixes();
  } else if (activeTab === 'segments') {
    renderSegments();
  } else if (activeTab === 'edges') {
    renderEdges();
  } else {
    renderTrace();
  }
}

function renderFixes() {
  for (const generation of caseData.generations) {
    const card = document.createElement('article');
    card.className = 'card';
    card.innerHTML = `<div class="card-head"><strong>${generation.id} · ${generation.deviceId}</strong>
      <span>${generation.confirmed ? '边界已确认' : '边界待确认'}</span></div>
      <p class="meta">代次 ${generation.generationNo}；边界 ${formatTime(generation.boundaryAt)}；${generation.note}</p>`;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = generation.confirmed ? '已确认设备更换边界' : '确认设备更换边界';
    button.disabled = generation.confirmed;
    button.onclick = async () => {
      await api(`/api/generations/${generation.id}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ boundaryAt: generation.boundaryAt })
      });
      await refresh();
    };
    card.appendChild(button);
    detailList.appendChild(card);
  }

  const template = document.getElementById('fix-template');
  for (const fix of caseData.fixes) {
    const node = template.content.firstElementChild.cloneNode(true);
    node.querySelector('strong').textContent = `${fix.id} · 代次 ${fix.generationNo}`;
    node.querySelector('.quality').textContent =
      `${fix.qualityStatus} / 质量 ${fix.qualityScore}`;
    node.querySelector('.meta').textContent =
      `${formatTime(fix.recordedAt)} · ${fix.deviceId} · (${fix.xM}, ${fix.yM}) ${fix.coordCrs} · 误差 ${fix.errorMajorM}m/${fix.errorCrs} · 活动 ${fix.activityIndex} · 参数 ${fix.parameterVersion}`;
    node.querySelector('.warnings-small').textContent = fix.warnings.join('；') || '无质量警告';
    const actions = node.querySelector('.card-actions');
    const reject = document.createElement('button');
    reject.textContent = '拒绝低质量定位';
    reject.onclick = async () => {
      await api(`/api/fixes/${fix.id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason: '人工判定低质量定位' })
      });
      await refresh();
    };
    const accept = document.createElement('button');
    accept.className = 'secondary';
    accept.textContent = '恢复采用';
    accept.onclick = async () => {
      await api(`/api/fixes/${fix.id}/accept`, { method: 'POST' });
      await refresh();
    };
    actions.append(reject, accept);
    detailList.appendChild(node);
  }
}

function renderSegments() {
  const segments = caseData.segments.filter(segment =>
    segment.candidate === 'STRUCTURAL' || segment.candidate === selectedCandidate);
  for (const segment of segments) {
    const card = document.createElement('article');
    card.className = 'card';
    const lower = formatDuration(segment.lowerBoundSeconds);
    const upper = formatDuration(segment.upperBoundSeconds);
    card.innerHTML = `<div class="card-head"><strong>${segment.type} · ${segment.state}</strong>
      <span>${lower} 至 ${upper}</span></div>
      <p class="meta">${formatTime(segment.startAt)} → ${formatTime(segment.endAt)}<br>
      定位：${segment.fixIds.join(', ')}<br>
      代次：${segment.generationNos.join(', ')}；设备：${segment.deviceIds.join(', ')}<br>
      参数：${segment.parameterVersions.join(', ')}</p>
      <p class="warnings-small">${segment.warnings.join('；') || '无分段警告'}${segment.manuallyAdjusted ? '；已人工调整' : ''}</p>`;
    if (segment.type === 'BEHAVIOR') {
      const select = document.createElement('select');
      select.className = 'state-select';
      for (const state of ['MOVING', 'STAY', 'UNCERTAIN']) {
        const option = document.createElement('option');
        option.value = state;
        option.textContent = state;
        option.selected = state === segment.state;
        select.appendChild(option);
      }
      const button = document.createElement('button');
      button.textContent = '调整此行为分段';
      button.onclick = async () => {
        await api('/api/segments/adjust', {
          method: 'POST',
          body: JSON.stringify({
            segmentId: segment.id,
            candidate: segment.candidate,
            state: select.value,
            note: '页面人工调整'
          })
        });
        await refresh();
      };
      card.append(select, button);
    }
    detailList.appendChild(card);
  }
}

function renderEdges() {
  for (const edge of caseData.edges) {
    const card = document.createElement('article');
    card.className = 'card';
    const speed = edge.speedMps === null ? '禁止计算' : `${edge.speedMps} m/s`;
    const distance = edge.distanceMeters === null ? '禁止距离判定' : `${edge.distanceMeters} m`;
    card.innerHTML = `<div class="card-head"><strong>${edge.fromFixId} → ${edge.toFixId}</strong>
      <span>${formatDuration(edge.elapsedSeconds)}</span></div>
      <p class="meta">距离：${distance}<br>速度：${speed}<br>
      代次：${edge.fromGenerationNo} → ${edge.toGenerationNo}<br>
      路径：${edge.drawPath ? '绘制连续轨迹' : '不绘制直线'}</p>
      <p class="warnings-small">${edge.reasons.join('；') || '普通连续边'}</p>`;
    detailList.appendChild(card);
  }
}

function renderTrace() {
  renderActions();
  const eventsCard = document.createElement('article');
  eventsCard.className = 'card';
  eventsCard.innerHTML = '<h2>设备与电池事件</h2>';
  for (const event of caseData.events) {
    const row = document.createElement('p');
    row.className = 'meta';
    row.textContent = `${formatTime(event.eventAt)} · ${event.deviceId} · ${event.type} · ${event.batteryV ?? '无电池值'}V`;
    eventsCard.appendChild(row);
  }
  detailList.appendChild(eventsCard);
}

function renderActions() {
  const card = document.createElement('article');
  card.className = 'card';
  card.innerHTML = '<h2>运行记录</h2>';
  if (caseData.actions.length === 0) {
    card.innerHTML += '<p class="meta">尚无人工操作。</p>';
  }
  for (const action of caseData.actions) {
    const row = document.createElement('p');
    row.className = 'meta';
    row.textContent = `${formatTime(action.actionAt)} · ${action.actionType} · ${action.targetType}:${action.targetId} · ${action.payloadJson}`;
    card.appendChild(row);
  }
  detailList.appendChild(card);
}

function formatTime(value) {
  if (!value) return '未设置';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function formatDuration(seconds) {
  if (seconds === null || seconds === undefined) return '未知';
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return `${hours}小时${minutes}分`;
}

for (const tab of document.querySelectorAll('.tabs button')) {
  tab.addEventListener('click', () => {
    activeTab = tab.dataset.tab;
    document.querySelectorAll('.tabs button').forEach(button => button.classList.remove('active'));
    tab.classList.add('active');
    renderDetails();
  });
}

candidateSelect.addEventListener('change', () => {
  selectedCandidate = candidateSelect.value;
  renderMap();
  renderDetails();
});

document.getElementById('retain-button').addEventListener('click', async () => {
  await api('/api/segments/retain-both', { method: 'POST' });
  await refresh();
});

document.getElementById('reset-button').addEventListener('click', async () => {
  await api('/api/import/reset', { method: 'POST' });
  await refresh();
});

document.getElementById('export-button').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(caseData, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'migration-review-run.json';
  link.click();
  URL.revokeObjectURL(url);
});

refresh().catch(error => {
  document.getElementById('warning-list').textContent = error.message;
});
