#!/bin/bash

##
# Easy Video Processor
## VERSION 1.0.2
##
# It's too easy if anything. Meaning; it's too simple, and only allows for two different outputs.
#


### FFMpeg in-line commands.  Set as variables here at the top of the script, for easy human editing and additions.
DESKTOP_FFMPEG_ARGS='-ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 750k -maxrate 750k -bufsize 1500k -pix_fmt yuv420p -vf colorspace=bt709:iall=bt601-6-625:fast=1,scale=720:720:force_original_aspect_ratio=increase:force_divisible_by=2,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1'
MOBILE_FFMPEG_ARGS='-ac 2 -c:a aac -b:a 96k -c:v libx264 -b:v 500k -maxrate 500k -bufsize 1000k -pix_fmt yuv420p -vf -vf colorspace=bt709:iall=bt601-6-625:fast=1,scale=480:480:force_original_aspect_ratio=increase:force_divisible_by=2,setsar=1:1,fps=24/1.001,deblock=filter=strong:block=4 -level:v 3.1'

function print_usage {
    printf "\nUSAGE:\n\t./easy-video-processor.sh -i [input_file] -t [desktop|mobile] -o [output_destination_and_filename]\n"
    exit 0
}

EXIT_STATUS='0'
function exit_on_error {
    if [[ $EXIT_STATUS != '0' ]]; then
        printf "\nEXIT_STATUS = $EXIT_STATUS\n"
        case $EXIT_STATUS in
            1)
                printf "\nNot enough arguments\n"
                print_usage
            ;;
            2)
                printf "\nHelp invoked.  Usage Printing...\n"
                print_usage
            ;;
            3)
                printf "\n\n\tYour specified input file could not be found on the filesystem. Please double check your path and filename.\n"
                print_usage
            ;;
            4)
                printf "\nYou must specify an input file\n"
                print_usage
            ;;
            5)
                printf "\n\n\tYour argument for '-t' could not be parsed.\n"
                print_usage
            ;;
            6)
                printf "\nYou must specify a target format\n"
                print_usage
            ;;
            7)
                printf "\nYou must specify an output filename  or a path AND filename\n"
                print_usage
            ;;
            255)
                printf "\n\n\tInvalid argument detected.\n"
                print_usage
            ;;
        esac
    fi
}

if [[ $# -eq 0 ]]; then
    EXIT_STATUS='1'
    exit_on_error
fi


while getopts "hi:t:o:" opt; do
    case ${opt} in
        h)
            EXIT_STATUS='2'
            exit_on_error
        ;;
        i)
            INPUT_FILE=$OPTARG
            if [[ ! -f $INPUT_FILE ]]; then
                EXIT_STATUS='3'
                exit_on_error
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
                *)
                    EXIT_STATUS='5'
                    exit_on_error
                ;;
            esac
        ;;
        o)
            OUTPUT_FILE=$OPTARG
        ;;
        :)
            EXIT_STATUS='255'
            exit_on_error
        ;;
    esac
done
shift $((OPTIND -1))

######
# Check that all arguments were used
######
if [[ $INPUT_FILE = '' ]]; then
    EXIT_STATUS='4'
    exit_on_error
fi
if [[ $FFMPEG_ARGS = '' ]]; then
    EXIT_STATUS='6'
    exit_on_error
fi
if [[ $OUTPUT_FILE = '' ]]; then
    EXIT_STATUS='7'
    exit_on_error
fi


######
# Perform the FFMPEG task
######
ffmpeg -y -i $INPUT_FILE $FFMPEG_ARGS $OUTPUT_FILE

# The -y argument tells FFMpeg to automatically overwrite a file if the output filename matches one on the filesystem.
# This allows us to bypass any extra human/keyboard inputs. Just in case an overwrite is called-for.
#
# ffmpeg does not produce regular unix/linux/posix/shell return value error codes.
# It can produce one of two return values:  0 = successful exit, and 255 = forced exit.
# It spits out strings of text on any other error or warning.  But doesn't actually return an individual code.
