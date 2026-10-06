// Local administration only. Tokens stay in the Firebase CLI credential store.
const {applicationDefault}=require('firebase-admin/app');
function adminCredential(useCli){
 if(!useCli) return applicationDefault();
 const {getGlobalDefaultAccount,getAccessToken}=require('firebase-tools/lib/auth');
 const {CLOUD_PLATFORM,FIREBASE_PLATFORM,USERINFO_EMAIL}=require('firebase-tools/lib/scopes');
 const account=getGlobalDefaultAccount();
 if(!account?.tokens?.refresh_token) throw Error('Sign in first with firebase login, or use Application Default Credentials without --firebase-cli.');
 return {async getAccessToken(){
  const token=await getAccessToken(account.tokens.refresh_token,[CLOUD_PLATFORM,FIREBASE_PLATFORM,USERINFO_EMAIL]);
  if(!token?.access_token) throw Error('Firebase CLI authorization expired. Run firebase login --reauth.');
  return {access_token:token.access_token,expires_in:Math.max(1,Math.floor(((token.expires_at??Date.now()+3600000)-Date.now())/1000))};
 }};
}
function adminFirestore(projectId,useCli){
 const {Firestore,getFirestore}=require('firebase-admin/firestore');
 if(!useCli) return getFirestore();
 const {getGlobalDefaultAccount}=require('firebase-tools/lib/auth');
 const {clientId,clientSecret}=require('firebase-tools/lib/api');
 const account=getGlobalDefaultAccount();
 if(!account?.tokens?.refresh_token) throw Error('Sign in first with firebase login.');
 // The Admin SDK's getFirestore() accepts only certificate/ADC credentials.
 // The underlying Firestore client also supports in-memory authorized-user OAuth.
 return new Firestore({projectId,credentials:{type:'authorized_user',client_id:clientId(),client_secret:clientSecret(),refresh_token:account.tokens.refresh_token}});
}
module.exports={adminCredential,adminFirestore};
