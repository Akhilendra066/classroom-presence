const {test}=require('node:test'); const fs=require('node:fs');
const {initializeTestEnvironment,assertFails}=require('@firebase/rules-unit-testing');
const {doc,setDoc,getDoc}=require('firebase/firestore');
test('Direct writes and reads are denied even to a claimed teacher',{skip:!process.env.FIRESTORE_EMULATOR_HOST},async()=>{
 const env=await initializeTestEnvironment({projectId:'demo-classroom-presence',firestore:{rules:fs.readFileSync('../firestore.rules','utf8')}});
 try {for(const role of ['STUDENT','TEACHER']) {const db=env.authenticatedContext(role,{role}).firestore();await assertFails(setDoc(doc(db,'users',role),{role:'TEACHER'}));await assertFails(getDoc(doc(db,'classSessions','secret')));}}finally{await env.cleanup();}
});
