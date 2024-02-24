from setuptools import setup, find_packages
from glob import glob


setup(
    name="toolchain",
    version="0.0.1",
    description="Toolchain for compiling Python & Other Libraries for Python Multiplatform",
    author="thisisthepy",
    author_email="",
    url="https://github.com/thisisthepy/toolchain",
    packages=[path.removesuffix(".py") for path in glob('toolchain/**/*.py', recursive=True)],
    install_requires=[
        "setuptools",
        "requests"
    ],
    dependency_links=[
        "git+https://github.com/thisisthepy/toolchain-ios.git@python3.11#egg=toolchain-ios"
    ],
    entry_points={
        'console_scripts': [
            "toolchain=toolchain.main:run_toolchain",
            "toolchain_targetver=toolchain.main:set_target_build_version",
            "toolchain_targetos=toolchain.main:set_target_os"
        ]
    },
    #package_data={'exampleproject': ['data/schema.json']}
)
