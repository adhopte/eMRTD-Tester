#!/bin/sh
# Download the OpenCV model-zoo face models (Apache-2.0) used for the selfie face match.
set -eu
DIR="${1:-data/models}"
mkdir -p "$DIR"
fetch() {
  name="$1"; url="$2"; sum="$3"
  if [ -f "$DIR/$name" ] && echo "$sum  $DIR/$name" | sha256sum -c - >/dev/null 2>&1; then return 0; fi
  curl -fsSL --retry 4 -o "$DIR/$name" "$url"
  echo "$sum  $DIR/$name" | sha256sum -c -
}
fetch face_detection_yunet_2023mar.onnx \
  https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx \
  8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
fetch face_recognition_sface_2021dec.onnx \
  https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec.onnx \
  0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79
