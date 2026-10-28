"""Install official API35 platform files when SDK metadata and disk disagree."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
REPOSITORY="https://dl.google.com/android/repository/"


def local(tag):return tag.rsplit("}",1)[-1]


def main():
    sdk=Path(os.environ.get("ANDROID_HOME",str(Path.home()/"AppData/Local/Android/Sdk")))
    destination=sdk/"platforms/android-35"
    if (destination/"android.jar").is_file():print("API35 platform already available");return
    data=urllib.request.urlopen(REPOSITORY+"repository2-3.xml",timeout=45).read()
    tree=ET.fromstring(data)
    package=next(n for n in tree.iter() if local(n.tag)=="remotePackage" and n.get("path")=="platforms;android-35")
    complete=next(n for n in package.iter() if local(n.tag)=="complete")
    fields={local(n.tag):n.text for n in complete}
    name=fields["url"];url=REPOSITORY+name
    checksum=next(n for n in complete if local(n.tag)=="checksum")
    algorithm=checksum.get("type","sha1");expected=checksum.text.strip()
    download=ROOT/"tools/downloads"/name;download.parent.mkdir(exist_ok=True)
    if not download.exists():urllib.request.urlretrieve(url,download)
    contents=download.read_bytes()
    if hashlib.new(algorithm,contents).hexdigest()!=expected:raise RuntimeError("Official platform checksum mismatch")
    extracted=ROOT/"tools/downloads/platform35-extracted";extracted.mkdir(exist_ok=True)
    with zipfile.ZipFile(download) as archive:
        for entry in archive.infolist():
            if not (extracted/entry.filename).resolve().is_relative_to(extracted.resolve()):raise RuntimeError("Unsafe SDK archive path")
        archive.extractall(extracted)
    jar=next(extracted.rglob("android.jar"))
    if destination.exists():raise RuntimeError(f"Refusing to overwrite an incomplete platform directory: {destination}")
    shutil.copytree(jar.parent,destination)
    receipt={"url":url,"official_checksum_algorithm":algorithm,"official_checksum":expected,"sha256":hashlib.sha256(contents).hexdigest(),"destination":str(destination)}
    (ROOT/"tools/downloads/android-platform35-receipt.json").write_text(json.dumps(receipt,indent=2)+"\n")
    print(json.dumps(receipt,indent=2))


if __name__=="__main__":main()
