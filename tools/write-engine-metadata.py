from pathlib import Path
import hashlib
import json
import struct

ROOT=Path(__file__).resolve().parents[1]
def main():
    base=ROOT/"build/android/native"
    result={"version":"1.4.0.5571","keeperfx_commit":"da038f03ab5f472dc570045f49cbfa76e7101ec4","abi":"arm64-v8a","min_api":35,"presentation":["vulkan","opengles2"],"world_rasterizer":"software","original_native_recompilation":False,"libraries":{}}
    for path in sorted((base/"arm64-v8a").glob("*.so")):
        data=path.read_bytes()
        if data[:5]!=b'\x7fELF\x02' or struct.unpack_from('<H',data,18)[0]!=183:raise RuntimeError(f"Not ARM64 ELF: {path}")
        offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
        align=[struct.unpack_from('<Q',data,offset+i*size+48)[0] for i in range(count) if struct.unpack_from('<I',data,offset+i*size)[0]==1]
        if min(align)<16384:raise RuntimeError(f"Not 16KB aligned: {path}: {align}")
        result["libraries"][path.name]={"sha256":hashlib.sha256(data).hexdigest(),"bytes":len(data),"load_alignments":align}
    (base/"engine.json").write_text(json.dumps(result,indent=2)+"\n")
    print("Verified ARM64 ELF/16KB alignment; wrote engine.json")
if __name__=="__main__":main()
