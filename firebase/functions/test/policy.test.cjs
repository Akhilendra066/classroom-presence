const {test}=require('node:test'); const assert=require('node:assert/strict');
const fs=require('node:fs'); const path=require('node:path');
const p=require('../lib/policy.js');
const beacons=[1,2,3,4].map(n=>({espId:`CLASSROOM_ESP_${n}`,number:n,wifiSsid:`CP_ROOM_A101_ESP_${n}`}));
const batch=(wifi,ble,at=1000)=>({wifi:{outcome:'SUCCESS',observations:wifi.flatMap((v,i)=>v===null?[]:[{espId:beacons[i].espId,rssiDbm:v,elapsedMs:at}]),detail:''},ble:{outcome:'SUCCESS',observations:ble.flatMap((v,i)=>v===null?[]:[{espId:beacons[i].espId,rssiDbm:v,elapsedMs:at}]),detail:''},evaluatedElapsedMs:at});
for(const f of JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../../shared/presence-fixtures.json')))) test(`Shared fixture ${f.name}`,()=>assert.equal(p.evaluate(batch(f.wifi,f.ble),p.defaultConfig,beacons).status,f.expected));
test('Stale and future observations fail closed',()=>{for(const now of [999,36001]){const b=batch([-55,-55,-55,-55],[-55,-55,-55,-55]);b.evaluatedElapsedMs=now;assert.equal(p.evaluate(b,p.defaultConfig,beacons).status,'INSUFFICIENT_DATA');}});
test('Unknown algorithm and simulated cloud points rejected',()=>{
 const pt={sessionId:'s',uid:'u',slot:0,capturedAtMs:10000,configVersion:1,batch:batch([-55,-55,-55,-55],[-55,-55,-55,-55]),result:{status:'INSIDE'},algorithmVersion:1,demo:false};
 assert.equal(p.checkpointSchema.safeParse(pt).success,true);
 assert.equal(p.checkpointSchema.safeParse({...pt,demo:true}).success,false);
 assert.equal(p.checkpointSchema.safeParse({...pt,algorithmVersion:2}).success,false);
});
test('Median uses Kotlin integer truncation',()=>{const b=batch([-90,-90,-90,-90],[-95,-95,-95,-95]);b.wifi.observations=b.wifi.observations.flatMap(o=>[o,{...o,rssiDbm:-89}]);assert.equal(p.evaluate(b,p.defaultConfig,beacons).wifi.medians.CLASSROOM_ESP_1,-89);});
function points(i,o,u=0){return [...Array(i).fill('INSIDE'),...Array(o).fill('OUTSIDE'),...Array(u).fill('UNCERTAIN')].map((status,slot)=>({slot,result:{status}}));}
test('Attendance boundaries',()=>{
 for(const [i,o,u,expected] of [[8,4,0,'ELIGIBLE'],[7,5,0,'BELOW_THRESHOLD'],[5,3,0,'ELIGIBLE'],[7,0,0,'INSUFFICIENT_COVERAGE'],[5,3,4,'BELOW_THRESHOLD']]) assert.equal(p.aggregate(points(i,o,u),p.defaultConfig).outcome,expected);
 assert.equal(p.aggregate([],p.defaultConfig).outcome,'INSUFFICIENT_COVERAGE');
 assert.throws(()=>p.aggregate([...points(8,4),{slot:0,result:{status:'INSIDE'}}],p.defaultConfig));
});
test('Role, membership and other-student isolation',()=>{
 const s={teacherUids:['t'],participantUids:['a','b']};
 assert.throws(()=>p.requireRole('STUDENT','TEACHER'));
 assert.throws(()=>p.requireMember('stranger',s));
 assert.throws(()=>p.requireMember('a',s,'b'));
 assert.doesNotThrow(()=>p.requireMember('t',s,'b'));
 assert.throws(()=>p.requireMember('t',s,'stranger'));
});
test('Collection times, early end and late uploads',()=>{
 assert.doesNotThrow(()=>p.validateCheckpointTiming(0,11000,10000,70000,300000,12000,900000));
 assert.throws(()=>p.validateCheckpointTiming(1,11000,10000,700000,300000,12000,900000));
 assert.throws(()=>p.validateCheckpointTiming(0,71000,10000,70000,300000,72000,900000));
 assert.throws(()=>p.validateCheckpointTiming(0,11000,10000,70000,300000,1000000,900000));
});
