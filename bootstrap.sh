#!/bin/bash

# nodes=("winterfell" "riverrun" "theeyrie" "casterlyrock" "highgarden")
# nodes=("docker1" "docker2")
nodes=("yunkai")

for node in ${nodes[@]}
do
  echo "ssh-keyscan -t rsa $node >> ~/.ssh/known_hosts"
  ssh-keyscan -t rsa $node >> ~/.ssh/known_hosts
done
