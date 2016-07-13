#!/bin/bash

# nodes=("winterfell" "riverrun" "theeyrie" "casterlyrock" "highgarden")
nodes=("winterfell")
first=0

if [ $first -eq 0 ] ; then
  for node in ${nodes[@]}
  do
    echo "ssh-keyscan -t rsa $node >> ~/.ssh/known_hosts"
    ssh-keyscan -t rsa $node >> ~/.ssh/known_hosts
  done
else
  for node in ${nodes[@]}
  do
    echo "192.168.99.102 $node"
  done
fi
