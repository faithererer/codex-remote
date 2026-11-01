import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const tools=path.resolve(root,'..','toolchains');
const env={...process.env};
env.DOTNET_CLI_HOME=path.join(tools,'dotnet-home');
env.DOTNET_GENERATE_ASPNET_CERTIFICATE='false';
env.DOTNET_CLI_TELEMETRY_OPTOUT='1';
env.NUGET_PACKAGES=path.join(tools,'nuget');
env.GRADLE_USER_HOME=path.join(tools,'gradle-cache');
env.JAVA_HOME ||= path.join(tools,'jdk-17.0.17+10');
env.ANDROID_HOME ||= path.join(tools,'android');
const dotnet=env.CODEX_DOTNET || path.join(tools,'dotnet','dotnet.exe');
const gradle=env.CODEX_GRADLE || path.join(tools,'gradle-8.13','bin','gradle.bat');
if(!env.CODEX_SIGNING_STORE||!env.CODEX_SIGNING_PASSWORD) throw Error('Set CODEX_SIGNING_STORE and CODEX_SIGNING_PASSWORD to your own signing key; private keys are not included.');
fs.writeFileSync(path.join(root,'android','local.properties'),'sdk.dir='+env.ANDROID_HOME.replaceAll('\\','/')+'\n');
function run(exe,args){const result=spawnSync(exe,args,{cwd:root,env,stdio:'inherit',shell:exe.endsWith('.bat')});if(result.status!==0)process.exit(result.status||1);}
run(dotnet,['publish','windows/CodexRemote.csproj','-c','Release','-r','win-x64','--self-contained','true','-o','dist/windows']);
run(gradle,['-p','android','assembleRelease','testReleaseUnitTest','--console=plain']);
console.log('Windows: dist/windows/CodexRemote.exe; APK: android/app/build/outputs/apk/release/app-release.apk');
