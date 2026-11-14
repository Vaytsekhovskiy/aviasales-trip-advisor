// Offline DOM smoke test. Run: node src/test/js/offer-dashboard-smoke.cjs target/offer-dashboard-test.html
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync(process.argv[2],'utf8');
const scripts=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)];
const data=JSON.parse(scripts[0][1]),code=new vm.Script(scripts[1][1]);
function run(input){
 class E{constructor(tag){this.tag=tag;this.children=[];this.events={};this.value='';}append(...c){this.children.push(...c);}replaceChildren(...c){this.children=c;}setAttribute(k,v){this[k]=v;}addEventListener(k,f){this.events[k]=f;}}
 const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new E('div'));return elements.get(id);};
 get('offer-data').textContent=JSON.stringify(input);get('sort').value='date';get('minN').value='0';
 code.runInNewContext({document:{getElementById:get,createElement:t=>new E(t),createElementNS:(n,t)=>new E(t)},console});
 return get;
}
const get=run(data);
assert.equal(get('kpis').children.length,4);
assert.equal(get('offers').children.length,2);
assert.equal(get('invalid').children.length,1);
assert.equal(get('chart').children[0].tag,'svg');
get('status').value='NOT_MATCHED';get('status').events.change();
assert.equal(get('offers').children.length,1);assert.equal(get('chart').children[0].tag,'p');
get('minN').value='1';get('minN').events.change();assert.match(get('count').textContent,/Выбрано 0/);
get('reset').events.click();get('route').value='SVO → OVB';get('route').events.change();assert.match(get('count').textContent,/Выбрано 1/);
get('reset').events.click();get('from').value='2027-01-01';get('from').events.change();assert.match(get('count').textContent,/Выбрано 0/);
get('reset').events.click();get('currency').value='USD';get('currency').events.change();assert.match(get('count').textContent,/Выбрано 0/);
get('reset').events.click();get('sort').value='median';get('sort').events.change();assert.match(get('offers').children[0].children[0].textContent,/6525/);
const empty=run({generatedAt:data.generatedAt,rows:[]});assert.match(empty('count').textContent,/Выбрано 0/);assert.equal(empty('next').disabled,true);
const large=run({generatedAt:data.generatedAt,rows:Array.from({length:75},(_,i)=>({...data.rows[0],id:String(i)}))});
assert.equal(large('offers').children.length,50);large('next').events.click();assert.equal(large('offers').children.length,25);
console.log('Offer dashboard JS: OK (filters, missing data, empty state, pagination, sorting)');
