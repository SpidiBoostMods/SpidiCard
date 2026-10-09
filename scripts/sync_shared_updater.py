#!/usr/bin/env python3
"""SpidiBoost: generate independent relocated copies of the coordinator and agent."""
from pathlib import Path
import json, shutil
root=Path(__file__).resolve().parents[1]
projects=[(root,'spidicard','SpidiCard','net.spidicard.shared'),(root.parent/'SpidiBan','spidiban','SpidiBan','net.spidiboost.spidiban.updates'),(root.parent/'SixubaaFP','sixubaafp','6ubaaFP','net.spidiboost.sixubaafp.updates'),(root.parent/'LittlePetStandalone','spidiboostlittlepet','LittlePet','com.spidiboost.updates')]
for project,id,name,package in projects:
 resource=id+'-shared-update-agent.jar'
 for mode,source in [('main','src/main/java'),('agent','src/sharedAgent/java'),('test','src/test/java')]:
  target=project/source/Path(package.replace('.','/'));target.mkdir(parents=True,exist_ok=True)
  for file in (root/'src/sharedTemplate'/mode).glob('*.java'):
   text=file.read_text().replace('PACKAGE',package).replace('AGENT_RESOURCE',resource)
   if file.name=='Artifact.java':text=text.replace('z.getEntry("'+resource+'")','z.getEntry(r.mod().id()+"-shared-update-agent.jar")')
   (target/file.name).write_text(text)
 for file in ['GitHubDownload.java','RestartCommand.java']:
  (project/'src/main/java'/Path(package.replace('.','/'))/file).write_text((root/'src/main/java/net/spidicard/update'/file).read_text().replace('net.spidicard.update',package))
 (project/'shared-updater.gradle').write_text('''sourceSets { sharedAgent {} }
sourceSets.main.compileClasspath += sourceSets.sharedAgent.output
sourceSets.main.runtimeClasspath += sourceSets.sharedAgent.output
sourceSets.test.compileClasspath += sourceSets.sharedAgent.output
sourceSets.test.runtimeClasspath += sourceSets.sharedAgent.output
def sharedAgentJar = tasks.register('sharedAgentJar', Jar) {
    from sourceSets.sharedAgent.output
    archiveFileName = 'RESOURCE'
    destinationDirectory = layout.buildDirectory.dir('shared-agent')
    manifest { attributes 'Main-Class': 'PACKAGE.BatchAgent', 'Implementation-Vendor': 'SpidiBoost' }
}
test { dependsOn sharedAgentJar }
processResources { from(sharedAgentJar) }
jar { from(sourceSets.sharedAgent.output) }
'''.replace('RESOURCE',resource).replace('PACKAGE',package))
 p=project/'build.gradle';s=p.read_text()
 if "apply from: 'shared-updater.gradle'" not in s:s+="\napply from: 'shared-updater.gradle'\n"
 if 'junit-jupiter' not in s:s+="\ndependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'; testRuntimeOnly 'org.junit.platform:junit-platform-launcher' }\ntest { useJUnitPlatform() }\n"
 p.write_text(s)
 p=project/'src/main/resources/fabric.mod.json';d=json.loads(p.read_text());d['name']='spidiboost.'+name;d['authors']=['SpidiBoost'];d['contact']={'homepage':'https://github.com/SpidiBoostMods/'+name,'sources':'https://github.com/SpidiBoostMods/'+name,'issues':'https://github.com/SpidiBoostMods/'+name+'/issues'}
 if id!='spidicard' and package+'.SharedBootstrap' not in d['entrypoints']['client']:d['entrypoints']['client'].append(package+'.SharedBootstrap')
 if id=='sixubaafp':d['icon']='assets/sixubaafp/icon.png'
 p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
print('Synced four independently bundled copies')
