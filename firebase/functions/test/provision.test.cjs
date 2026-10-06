const {test}=require('node:test');
const assert=require('node:assert/strict');
const {promisify}=require('node:util');
const {execFile}=require('node:child_process');
const {initializeApp,deleteApp}=require('firebase-admin/app');
const {getAuth}=require('firebase-admin/auth');
const {getFirestore}=require('firebase-admin/firestore');
const {resolve}=require('node:path');
const enabled=Boolean(process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_AUTH_EMULATOR_HOST);
test('Administrative provisioning preserves roster, calibration and rejects conflicting roles',{skip:!enabled,timeout:60000},async()=>{
 const project='demo-provisioning';
 const app=initializeApp({projectId:project},'provision-test'),auth=getAuth(app),db=getFirestore(app);
 try {
  const suffix=Date.now();
  const teacher=await auth.createUser({email:`teacher-${suffix}@example.test`});
  const student=await auth.createUser({email:`student-${suffix}@example.test`});
  const next=await auth.createUser({email:`next-${suffix}@example.test`});
  const run=(a,b)=>promisify(execFile)(process.execPath,[resolve(__dirname,'../scripts/provision.cjs'),a,b],{env:{...process.env,GCLOUD_PROJECT:project}});
  await run(teacher.email,student.email);
  assert.equal((await auth.getUser(teacher.uid)).customClaims.role,'TEACHER');
  assert.equal((await auth.getUser(student.uid)).customClaims.role,'STUDENT');
  await db.doc('classrooms/ROOM_A101').update({activeConfigVersion:7});
  await run(teacher.email,next.email);
  await run(teacher.email,next.email);
  const roster=(await db.doc('classes/class-a101').get()).data();
  assert.deepEqual(new Set(roster.studentUids),new Set([student.uid,next.uid]));
  assert.deepEqual(roster.teacherUids,[teacher.uid]);
  assert.equal((await db.doc('classrooms/ROOM_A101').get()).get('activeConfigVersion'),7);
  await assert.rejects(run(student.email,next.email),/different role/);
  await assert.rejects(run(teacher.email,teacher.email),/different accounts/);
 } finally {await deleteApp(app);}
});
