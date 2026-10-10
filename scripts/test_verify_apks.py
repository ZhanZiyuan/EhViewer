import struct
import unittest
from verify_apks import elf


def sample(abi='arm64-v8a', align=16384, offset=0, vaddr=0, count=1):
    is64 = abi != 'armeabi-v7a'
    data = bytearray(128)
    data[:6] = b'\x7fELF' + bytes([2 if is64 else 1, 1])
    struct.pack_into('<H', data, 18, {'arm64-v8a': 183, 'x86_64': 62, 'armeabi-v7a': 40}[abi])
    if is64:
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, count)
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, offset, vaddr, 0, 32, 32, align)
    else:
        struct.pack_into('<I', data, 28, 52)
        struct.pack_into('<HH', data, 42, 32, count)
        struct.pack_into('<IIIIIIII', data, 52, 1, offset, vaddr, 0, 32, 32, 5, align)
    return bytes(data)


class ElfValidationTest(unittest.TestCase):
    def test_all_abis(self):
        for abi in ['arm64-v8a', 'x86_64', 'armeabi-v7a']:
            self.assertEqual([16384], elf(sample(abi), abi))

    def test_rejects_four_kib_64_bit(self):
        for abi in ['arm64-v8a', 'x86_64']:
            with self.assertRaisesRegex(ValueError, '16KB'):
                elf(sample(abi, align=4096), abi)

    def test_allows_four_kib_32_bit(self):
        self.assertEqual([4096], elf(sample('armeabi-v7a', align=4096), 'armeabi-v7a'))

    def test_rejects_wrong_machine(self):
        with self.assertRaisesRegex(ValueError, 'machine'):
            elf(sample('x86_64'), 'arm64-v8a')

    def test_rejects_misaligned_load(self):
        with self.assertRaisesRegex(ValueError, 'alignment'):
            elf(sample(offset=1), 'arm64-v8a')

    def test_rejects_non_power_of_two(self):
        with self.assertRaisesRegex(ValueError, 'alignment'):
            elf(sample(align=20000), 'arm64-v8a')

    def test_rejects_empty_load_table(self):
        with self.assertRaisesRegex(ValueError, 'no LOAD'):
            elf(sample(count=0), 'arm64-v8a')

    def test_rejects_bad_magic_and_class(self):
        with self.assertRaises(ValueError):
            elf(b'not ELF', 'arm64-v8a')
        with self.assertRaisesRegex(ValueError, 'class'):
            elf(sample('armeabi-v7a'), 'arm64-v8a')


if __name__ == '__main__':
    unittest.main()
