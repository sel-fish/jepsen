#!/bin/bash

path=`dirname $0`
cd $path

version=$(head -1 jepsen/project.clj |awk -F '"| ' '{print $(NF-1)}')

cd jepsen
lein uberjar
cd ..

mvn install:install-file \
    -Dfile=jepsen/target/jepsen-$version-standalone.jar \
    -DgroupId=com.mogujie.mst \
    -DartifactId=jepsen \
    -Dversion=$version \
    -Dpackaging=jar \
    -DgeneratePom=true \
    -DcreateChecksum=true\
    -DlocalRepositoryPath=$HOME/.m2/repository
