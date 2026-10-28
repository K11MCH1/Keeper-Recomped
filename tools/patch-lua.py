"""Keep Lua5.1's trailing string allocation compatible with Android FORTIFY."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def main():
    path=ROOT/"third_party/lua-5.1.5/src/lgc.c"
    text=path.read_text()
    old="    weakkey = (strchr(svalue(mode), 'k') != NULL);\n    weakvalue = (strchr(svalue(mode), 'v') != NULL);"
    new="""    /* TString stores characters after its header allocation. Android's
       fortified strchr infers a zero-sized subobject for the old ts+1 idiom.
       Scan using the actual Lua string length, preserving NUL semantics. */
    const char *modechars = svalue(mode);
    size_t modelength = rawtsvalue(mode)->tsv.len;
    for (size_t n = 0; n < modelength && modechars[n] != '\\0'; ++n) {
      weakkey |= modechars[n] == 'k';
      weakvalue |= modechars[n] == 'v';
    }"""
    if new in text:return
    if text.count(old)!=1:raise RuntimeError("Lua GC source anchor changed")
    path.write_text(text.replace(old,new),newline="\n")
    print("Applied Android FORTIFY-compatible Lua weak-table mode scan")


if __name__=="__main__":main()
