#!/usr/bin/env python3
"""Check actual CMake build databases; app JNI/algorithms must be supplied by Rust."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
results = []
for configuration in ['Debug', 'RelWithDebInfo']:
    for abi in ['arm64-v8a', 'armeabi-v7a', 'x86_64']:
        databases = list((root/'app/.cxx'/configuration).glob('*/'+abi+'/compile_commands.json'))
        if not databases:
            raise RuntimeError('Missing CMake database: '+configuration+'/'+abi)
        # Historical tools/default* snapshots are not build inputs. Choose the latest actual build.
        database = max(databases, key=lambda p: p.stat().st_mtime_ns)
        sources = [Path(row['file']).resolve() for row in json.loads(database.read_text())]
        app_sources = [str(p) for p in sources if (root/'app/src/main/cpp') in p.parents]
        if app_sources:
            raise RuntimeError('App C/C++ still compiled: '+str(app_sources))
        if not any(p.name == 'rust-link-anchor.S' for p in sources):
            raise RuntimeError('Rust link anchor missing')
        results.append({'configuration': configuration, 'abi': abi, 'database': str(database.relative_to(root)), 'compiled_sources': len(sources), 'app_c_sources': app_sources})
print(json.dumps(results, indent=2))
