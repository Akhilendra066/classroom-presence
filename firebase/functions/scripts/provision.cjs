// Trusted local administration only; never bundle this code in the Android app.
const {initializeApp}=require('firebase-admin/app');
const {adminCredential,adminFirestore}=require('./admin-credential.cjs');
const {getAuth}=require('firebase-admin/auth'); const {FieldValue}=require('firebase-admin/firestore');
const {defaultConfig}=require('../lib/policy');
const project=process.env.GCLOUD_PROJECT;
if(!project) throw Error('Set GCLOUD_PROJECT to your Firebase project ID');
initializeApp({projectId:project,credential:adminCredential(process.argv.includes('--firebase-cli'))});
async function main(){
 const [teacherEmail,studentEmail]=process.argv.slice(2).filter(arg=>arg!=='--firebase-cli');
 if(!teacherEmail || !studentEmail) throw Error('Usage: node scripts/provision.cjs teacher@email student@email (accounts must already exist in Auth)');
 const auth=getAuth(),db=adminFirestore(project,process.argv.includes('--firebase-cli')); const teacher=await auth.getUserByEmail(teacherEmail),student=await auth.getUserByEmail(studentEmail);
 if(teacher.uid===student.uid) throw Error('Teacher and student must be different accounts.');
 if((teacher.customClaims?.role && teacher.customClaims.role!=='TEACHER') || (student.customClaims?.role && student.customClaims.role!=='STUDENT')) throw Error('An account already has a different role; review it before changing trusted access.');
 const beacons=[1,2,3,4].map(n=>({espId:`CLASSROOM_ESP_${n}`,number:n,wifiSsid:`CP_ROOM_A101_ESP_${n}`}));
 await db.runTransaction(async batch=>{
 const room=db.doc('classrooms/ROOM_A101'),classRef=db.doc('classes/class-a101');
 const existing=await batch.get(room),existingClass=await batch.get(classRef);
 if(existingClass.exists && existingClass.data().roomId!=='ROOM_A101') throw Error('Existing class-a101 belongs to a different room; refusing to overwrite it.');
 batch.set(db.doc(`users/${teacher.uid}`),{uid:teacher.uid,role:'TEACHER',email:teacherEmail});
 batch.set(db.doc(`users/${student.uid}`),{uid:student.uid,role:'STUDENT',email:studentEmail});
 if(existingClass.exists) batch.update(classRef,{teacherUids:FieldValue.arrayUnion(teacher.uid),studentUids:FieldValue.arrayUnion(student.uid)});
 else batch.create(classRef,{title:'Classroom A101',roomId:'ROOM_A101',teacherUids:[teacher.uid],studentUids:[student.uid]});
 batch.set(db.doc(`classes/class-a101/members/${student.uid}`),{uid:student.uid,role:'STUDENT'});
 if(!existing.exists){batch.create(room,{teacherUids:[teacher.uid],beacons,activeConfigVersion:1});batch.create(room.collection('presenceConfigs').doc('1'),{config:defaultConfig,createdBy:'administration'});}
 else batch.update(room,{teacherUids:FieldValue.arrayUnion(teacher.uid)});
 });
 await auth.setCustomUserClaims(teacher.uid,{...(teacher.customClaims??{}),role:'TEACHER'});
 await auth.setCustomUserClaims(student.uid,{...(student.customClaims??{}),role:'STUDENT'});
 console.log('Provisioned roles and class. Existing roster and calibration preserved. Sign out/in to refresh claims; calibrate before starting a production session.');
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
