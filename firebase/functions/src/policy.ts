import {z} from "zod";
export const statuses = ["INSIDE", "OUTSIDE", "UNCERTAIN", "INSUFFICIENT_DATA"] as const;
export type Status = typeof statuses[number];
const number = z.number().int();
export const configSchema = z.object({
 version:number.positive(), calibrated:z.boolean(), wifiInsideDbm:number.min(-127).max(-1), bleInsideDbm:number.min(-127).max(-1),
 wifiOutsideDbm:number.min(-127).max(-1), bleOutsideDbm:number.min(-127).max(-1), minDetected:number.min(1).max(4),
 minStrong:number.min(1).max(4), minWeak:number.min(1).max(4), maxSpreadDb:number.min(1).max(100), maxAgeMs:number.min(1000).max(60000),
 bleDurationMs:number.min(1000).max(30000), intervalMs:number.min(30001).max(600000), totalSlots:number.min(1).max(24),
 minValid:number.min(1).max(24), insidePercent:number.min(1).max(100)
}).strict().superRefine((c,ctx)=>{
 if(c.wifiOutsideDbm>=c.wifiInsideDbm || c.bleOutsideDbm>=c.bleInsideDbm || c.minStrong<c.minDetected || c.minWeak<c.minDetected || c.minValid>c.totalSlots || c.intervalMs<=c.bleDurationMs)
  ctx.addIssue({code:z.ZodIssueCode.custom,message:"Invalid threshold or attendance policy"});
});
export type Config = z.infer<typeof configSchema>;
export const defaultConfig:Config = {version:1,calibrated:false,wifiInsideDbm:-72,bleInsideDbm:-75,wifiOutsideDbm:-82,bleOutsideDbm:-85,
 minDetected:3,minStrong:3,minWeak:3,maxSpreadDb:30,maxAgeMs:35000,bleDurationMs:25000,intervalMs:300000,totalSlots:12,minValid:8,insidePercent:60};
export const beaconSchema = z.object({espId:z.string().regex(/^[A-Z0-9_]{1,64}$/),number:number.min(1).max(4),wifiSsid:z.string().min(1).max(32)}).strict();
export type Beacon = z.infer<typeof beaconSchema>;
export const observationSchema = z.object({espId:z.string().max(64),rssiDbm:number.min(-127).max(-1),elapsedMs:number.nonnegative()}).strict();
const scanSchema = z.object({outcome:z.enum(["SUCCESS","DISABLED","PERMISSION_DENIED","FAILED","TIMEOUT"]),observations:z.array(observationSchema).max(5000),detail:z.string().max(200).default("")}).strict();
export const batchSchema = z.object({wifi:scanSchema,ble:scanSchema,evaluatedElapsedMs:number.nonnegative()}).strict();
export type Batch = z.infer<typeof batchSchema>;
export type Evidence = {detected:number,strong:number,weak:number,spreadDb:number|null,medians:Record<string,number>};
export type Result = {status:Status,score:number,wifi:Evidence,ble:Evidence,reasons:string[]};
export const checkpointSchema = z.object({sessionId:z.string().min(1).max(128),uid:z.string().min(1).max(128),slot:number.min(0).max(23),capturedAtMs:number.positive(),
 configVersion:number.positive(),batch:batchSchema,result:z.unknown(),algorithmVersion:z.literal(1),demo:z.literal(false)}).strict();
export type Point = {slot:number,result:Result};
export function evaluate(batch:Batch, c:Config, beacons:Beacon[]):Result {
 configSchema.parse(c);
 const ids = new Set(beacons.map(b=>b.espId));
 function evidence(scan:Batch["wifi"],inside:number,outside:number):Evidence {
  const grouped:Record<string,number[]>={};
  for(const o of scan.observations) if(ids.has(o.espId) && batch.evaluatedElapsedMs-o.elapsedMs>=0 && batch.evaluatedElapsedMs-o.elapsedMs<=c.maxAgeMs) (grouped[o.espId]??=[]).push(o.rssiDbm);
  const medians:Record<string,number>={};
  for(const [id,values] of Object.entries(grouped)) { values.sort((a,b)=>a-b); const mid=Math.floor(values.length/2); medians[id]=values.length%2?values[mid]:Math.trunc((values[mid-1]+values[mid])/2); }
  const v=Object.values(medians);
  return {detected:v.length,strong:v.filter(v=>v>=inside).length,weak:v.filter(v=>v<=outside).length,spreadDb:v.length?Math.max(...v)-Math.min(...v):null,medians};
 }
 const wifi=evidence(batch.wifi,c.wifiInsideDbm,c.wifiOutsideDbm),ble=evidence(batch.ble,c.bleInsideDbm,c.bleOutsideDbm),reasons:string[]=[];
 if(batch.wifi.outcome!=="SUCCESS") reasons.push(`WIFI_${batch.wifi.outcome}`);
 if(batch.ble.outcome!=="SUCCESS") reasons.push(`BLE_${batch.ble.outcome}`);
 if(wifi.detected<c.minDetected) reasons.push("WIFI_TOO_FEW_FRESH_BEACONS");
 if(ble.detected<c.minDetected) reasons.push("BLE_TOO_FEW_FRESH_BEACONS");
 if(reasons.length) return {status:"INSUFFICIENT_DATA",score:0,wifi,ble,reasons};
 const balanced=wifi.spreadDb!<=c.maxSpreadDb && ble.spreadDb!<=c.maxSpreadDb;
 const status:Status = wifi.weak>=c.minWeak && ble.weak>=c.minWeak?"OUTSIDE":wifi.strong>=c.minStrong && ble.strong>=c.minStrong && balanced?"INSIDE":"UNCERTAIN";
 reasons.push(`WIFI_STRONG_${wifi.strong}_WEAK_${wifi.weak}`,`BLE_STRONG_${ble.strong}_WEAK_${ble.weak}`);
 if(!balanced) reasons.push("SIGNAL_IMBALANCE");
 reasons.push(status==="INSIDE"?"BOTH_TECHNOLOGIES_SUPPORT_INSIDE":status==="OUTSIDE"?"BOTH_TECHNOLOGIES_SUPPORT_OUTSIDE":"BOUNDARY_OR_CONFLICTING_EVIDENCE");
 return {status,score:wifi.strong+ble.strong,wifi,ble,reasons};
}
export function aggregate(points:Point[],c:Config) {
 configSchema.parse(c);
 if(new Set(points.map(p=>p.slot)).size!==points.length || points.some(p=>p.slot<0 || p.slot>=c.totalSlots)) throw new Error("Invalid slots");
 const count=(s:Status)=>points.filter(p=>p.result.status===s).length;
 const inside=count("INSIDE"),outside=count("OUTSIDE"),uncertain=count("UNCERTAIN"),valid=inside+outside+uncertain;
 const outcome=valid<c.minValid?"INSUFFICIENT_COVERAGE":inside*100>=c.insidePercent*valid?"ELIGIBLE":"BELOW_THRESHOLD";
 return {outcome,inside,outside,uncertain,valid,expected:c.totalSlots,missing:c.totalSlots-valid,insidePercentage:valid?Math.floor(inside*100/valid):0};
}
export function validateCheckpointTiming(slot:number,captured:number,start:number,end:number,interval:number,now:number,grace:number) {
 const due=start+slot*interval;
 if(captured<due || captured>due+60000 || captured>=end || captured>now+5000 || now>end+grace) throw new Error("Checkpoint outside permitted session window");
}
export function requireRole(actual:unknown,expected:string) { if(actual!==expected) throw new Error("Role denied"); }
export function requireMember(uid:string,session:{teacherUids:string[],participantUids:string[]},target?:string) {
 const teacher=session.teacherUids.includes(uid),student=session.participantUids.includes(uid);
 if(!teacher && !student) throw new Error("Session access denied");
 if(target && !teacher && target!==uid) throw new Error("Other student denied");
 if(target && !session.participantUids.includes(target)) throw new Error("Participant missing");
}
