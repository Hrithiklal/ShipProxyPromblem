#!/bin/bash
javac *.java
if [ -f "ShipProxy.class" ]; then
    java ShipProxy
elif [ -f "OffshoreProxy.class" ]; then
    java OffshoreProxy
else
    echo "No valid class found"
    exit 1
fi