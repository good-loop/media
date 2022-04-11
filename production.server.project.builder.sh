#!/bin/bash
ssh winterwell@baker.good-loop.com bash <<EOF 
/home/winterwell/config/build-scripts/builder.sh \
BUILD_TYPE="production" \
PROJECT_NAME="media" \
BRANCH_NAME="master" \
NAME_OF_SERVICE="mediaserver" \
GIT_REPO_URL="github.com:good-loop/media" \
PROJECT_ROOT_ON_SERVER="/home/winterwell/media" \
PROJECT_USES_BOB="yes" \
PROJECT_USES_NPM="no" \
PROJECT_USES_WEBPACK="no" \
PROJECT_USES_JERBIL="no" \
PROJECT_USES_WWAPPBASE_SYMLINK="no"
EOF