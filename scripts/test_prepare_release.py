import copy
import os
from pathlib import Path
import stat
import tempfile
import unittest

from prepare_release import attachment_plan, private_directory, version_from_tag


def metadata():
    return {'artifactType': {'type': 'APK'}, 'applicationId': 'moe.tarsin.ehviewer', 'elements': [
        {'filters': [] if abi == 'universal' else [{'filterType': 'ABI', 'value': abi}],
         'versionCode': 180066, 'versionName': '1.15.2', 'outputFile': f'app-{abi}-release.apk'}
        for abi in ['arm64-v8a', 'armeabi-v7a', 'universal', 'x86_64']]}


class ReleasePackagingTest(unittest.TestCase):
    def test_normalizes_optional_v_prefix(self):
        for tag in ['1.15.2', 'v1.15.2']:
            self.assertEqual('1.15.2', version_from_tag(tag))
        self.assertEqual('1.15.2-RC1', version_from_tag('v1.15.2-RC1'))

    def test_rejects_unsafe_or_nonrelease_tags(self):
        for tag in ['', 'v', '../1.15.2', 'v1.15', '01.15.2', '1.15.2-SNAPSHOT', '1.15.2-default',
                    '1.15.2-marshmallow', 'v1.15.2\n', '1.15.2;echo', '1.15.2+meta']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version_from_tag(tag)

    def test_exact_four_names_with_no_v_or_flavor(self):
        names = {r['name'] for r in attachment_plan(metadata(), 'v1.15.2')}
        self.assertEqual({f'EhViewer-1.15.2-{a}.apk' for a in ['arm64-v8a', 'armeabi-v7a', 'universal', 'x86_64']}, names)

    def test_missing_or_extra_abi_fails(self):
        for mutate in [lambda x: x.pop(), lambda x: x.append(copy.deepcopy(x[0])),
                       lambda x: x[0]['filters'][0].update(value='x86')]:
            value = metadata()
            mutate(value['elements'])
            with self.assertRaises(ValueError): attachment_plan(value, 'v1.15.2')

    def test_version_name_must_match_normalized_tag(self):
        value = metadata()
        value['elements'][2]['versionName'] = '1.15.2-SNAPSHOT'
        with self.assertRaises(ValueError): attachment_plan(value, 'v1.15.2')
        with self.assertRaises(ValueError): attachment_plan(metadata(), 'v1.15.3')

    def test_version_codes_must_be_positive_and_consistent(self):
        for code in [-1, 0, True, '180066', 180067]:
            value = metadata()
            value['elements'][1]['versionCode'] = code
            with self.assertRaises(ValueError): attachment_plan(value, 'v1.15.2')

    def test_application_identity_and_artifact_type_are_fixed(self):
        for key, value in [('applicationId', 'moe.tarsin.ehviewer.m'), ('artifactType', {'type': 'BUNDLE'})]:
            data = metadata()
            data[key] = value
            with self.assertRaises(ValueError): attachment_plan(data, 'v1.15.2')

    def test_filters_and_input_paths_are_validated(self):
        for filename in ['../outside.apk', '/tmp/out.apk', 'sub/out.apk', 'sub\\out.apk', 'mapping.txt']:
            value = metadata()
            value['elements'][0]['outputFile'] = filename
            with self.assertRaises(ValueError): attachment_plan(value, 'v1.15.2')
        value = metadata()
        value['elements'][0]['filters'].append({'filterType': 'DENSITY', 'value': 'hdpi'})
        with self.assertRaises(ValueError): attachment_plan(value, 'v1.15.2')

    def test_duplicate_input_file_is_rejected(self):
        value = metadata()
        value['elements'][1]['outputFile'] = value['elements'][0]['outputFile']
        with self.assertRaises(ValueError): attachment_plan(value, 'v1.15.2')

    def test_local_retention_is_owner_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'private'
            private_directory(path)
            self.assertEqual(0o700, stat.S_IMODE(path.stat().st_mode))
            os.chmod(path, 0o755)
            with self.assertRaises(ValueError): private_directory(path)
            link = Path(tmp)/'link'
            link.symlink_to(path, target_is_directory=True)
            with self.assertRaises(ValueError): private_directory(link)


if __name__ == '__main__':
    unittest.main()
