// Run: node src/test/js/delay-dashboard-smoke.cjs output/delay-dashboard-preview.html
// Exercises the embedded script with a minimal DOM/canvas double, no npm or network.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(process.argv[2], 'utf8');
const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)];
const data = JSON.parse(scripts[0][1]);
const code = new vm.Script(scripts[1][1]);
function run(input) {
  const elements = new Map();
  const context = new Proxy({}, {get: () => () => {}, set: () => true});
  class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.style = {}; this.events = {}; this.value = 'central'; }
    append(...children) { this.children.push(...children); }
    setAttribute(key, value) { this[key] = value; }
    addEventListener(event, fn) { this.events[event] = fn; }
    getContext() { return context; }
    getBoundingClientRect() { return {left: 0, top: 0, width: 850, height: 850}; }
  }
  const document = {
    createElement: tag => new Element(tag), createElementNS: (ns, tag) => new Element(tag),
    getElementById: id => {
      if (!elements.has(id)) elements.set(id, new Element('div'));
      return elements.get(id);
    }
  };
  document.getElementById('dashboard-data').textContent = JSON.stringify(input);
  code.runInNewContext({document, console, innerWidth: 1440, innerHeight: 1000});
  assert.equal(elements.get('kpis').children.length, 8);
  assert.equal(elements.get('heatmap').children[0].children.length, 8);
  assert.ok(elements.get('scatter-count').textContent.includes(String(input.recovery.points.length).replace(/ /g,'')) || input.recovery.points.length > 999);
  const range = elements.get('range'); range.value = 'all'; range.events.change();
  assert.ok(elements.get('scatter-count').textContent.endsWith('вне диапазона 0'));
  elements.get('scatter').events.mousemove({clientX: 100, clientY: 100});
  elements.get('scatter').events.mouseleave();
}
run(data);
const empty = structuredClone(data);
empty.recovery = {points: [], median: null, recovered: null, worsened: null, unchanged: null, low: 0, high: 1};
empty.airlines = []; empty.airports = []; empty.daily = []; empty.from = null; empty.to = null;
for (const group of [empty.total, ...empty.weekdays, ...empty.heatmap]) {
  Object.assign(group, {n:0, arrivalN:0, flights:0, median:null, mean:null, p15:null, p30:null, p60:null, cancellations:null});
}
run(empty);
console.log('Dashboard JavaScript smoke test: passed (populated + empty + full scatter range)');
