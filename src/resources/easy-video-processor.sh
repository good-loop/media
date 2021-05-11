#!/bin/bash

##
# Easy Video Processor
## VERSION 1.0.0
##
# It's too easy if anything. Meaning; it's too simple, and only allows for three different outputs.
#
# TODO:
## if not enough arguments passed -- print usage and exit

### FFMpeg in-line commands.  Set as variables here at the top of the script, for easy human editing and additions.
DESKTOP_FFMPEG_ARGS='-ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 750k -maxrate 750k -bufsize 1500k -vf scale=-1:720,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1'
MOBILE_FFMPEG_ARGS='-ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 500k -maxrate 500k -bufsize 1000k -vf scale=-2:480,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1'
PORTRAIT_FFMPEG_ARGS='-ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 500k -maxrate 500k -bufsize 1000k -vf scale=-2:720,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1'


USAGE="\nUSAGE:\n\t./easy-video-processor.sh -i [input_file] -t [desktop|mobile|portrait] -o [output_destination_and_filename]\n"

if [[ $# -eq 0 ]]; then
    echo -e $USAGE
    exit 0
fi


while getopts "hi:t:o:" opt; do
    case ${opt} in
        h)
            echo -e $USAGE
            exit 0
        ;;
        i)
            INPUT_FILE=$OPTARG
            if [[ ! -f $INPUT_FILE ]]; then
                printf "\n\n\tYour specified input file could not be found on the filesystem. Please double check your path and filename.\n"
                echo -e $USAGE
                exit 0
            fi
        ;;
        t)
            TRANSCODE_TARGET=$OPTARG
            case $TRANSCODE_TARGET in
                desktop|DESKTOP)
                    FFMPEG_ARGS=$DESKTOP_FFMPEG_ARGS
                ;;
                mobile|MOBILE)
                    FFMPEG_ARGS=$MOBILE_FFMPEG_ARGS
                ;;
                portrait|PORTRAIT)
                    FFMPEG_ARGS=$PORTRAIT_FFMPEG_ARGS
                ;;
                *)
                    printf "\n\n\tYour argument for '-t' could not be parsed.\n"
                    echo -e $USAGE
                    exit 0
                ;;
            esac
        ;;
        o)
            OUTPUT_FILE=$OPTARG
        ;;
        :)
            printf "\n\n\tInvalid argument detected.\n"
            echo -e $USAGE
            exit 0
        ;;
    esac
done
shift $((OPTIND -1))

######
# Check that all arguments were used
######
if [[ $INPUT_FILE = '' ]]; then
    printf "\nYou must specify an input file\n"
    echo -e $USAGE
    exit 0
fi
if [[ $FFMPEG_ARGS = '' ]]; then
    printf "\nYou must specify a target format\n"
    echo -e $USAGE
    exit 0
fi
if [[ $OUTPUT_FILE = '' ]]; then
    printf "\nYou must specify an output filename  or a path AND filename\n"
    echo -e $USAGE
    exit 0
fi


######
# Perform the FFMPEG task
######
ffmpeg -i $INPUT_FILE $FFMPEG_ARGS $OUTPUT_FILE