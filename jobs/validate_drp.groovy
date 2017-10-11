import util.Common
Common.makeFolders(this)

def folder = 'sqre'

def j = matrixJob("${folder}/validate_drp") {
  description('Execute validate_drp and ship the results to the squash qa-dashboard.')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('master')
  concurrentBuild()

  multiscm {
    git {
      remote {
        github('lsst/lsstsw')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('lsstsw')
        // cloneOptions { shallow() }
      }
    }
    git {
      remote {
        github('lsst-sqre/buildbot-scripts')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('buildbot-scripts')
        // cloneOptions { shallow() }
      }
    }
    // jenkins can't properly clone a git-lfs repo (yet) due to the way it
    // invokes git.  Jenkins is managing the basic checkout but we need to do a
    // manual `git lfs pull`.  see:
    // https://issues.jenkins-ci.org/browse/JENKINS-30318
    git {
      remote {
        github('lsst/validation_data_hsc')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('validation_data_hsc')
        // cloneOptions { shallow() }
      }
    }
  }

  triggers {
    // Run once a day starting at ~17:00 project time.  This is to allow a
    // ~10-14 hour build window before princeton buisness hours.
    cron('H 17 * * *')
  }

  axes {
    label('label', 'centos-7')
    // hsc & decam are disabled as they are currently broken
    //text('dataset', 'cfht', 'hsc', 'decam')
    text('dataset', 'cfht', 'hsc')
    text('python', 'py3')
  }

  combinationFilter('!(label=="centos-6" && python=="py3")')

  wrappers {
    colorizeOutput('gnome-terminal')
    credentialsBinding {
      usernamePassword(
        'SQUASH_USER',
        'SQUASH_PASS',
        'squash-api-user'
      )
      string('SQUASH_URL', 'squash-api-url')
      string('CMIRROR_S3_BUCKET', 'cmirror-s3-bucket')
    }
  }

  wrappers {
    colorizeOutput('gnome-terminal')
    credentialsBinding {
      usernamePassword(
        'SQUASH_NEW_USER',
        'SQUASH_NEW_PASS',
        'squash-new-api-user'
      )
      string('SQUASH_NEW_URL', 'squash-new-api-url')
      string('CMIRROR_S3_BUCKET', 'cmirror-s3-bucket')
    }
  }


  environmentVariables(
    // do not set 'master' as a ref as it will override a repos.yaml default
    // branch
    //BRANCH:    'master',
    PRODUCT:   'validate_drp',
    SKIP_DEMO: true,
    SKIP_DOCS: true,
    NO_FETCH:  false,
    // anything in thid dir will be saved as a build artifact
    ARCHIVE:   '$WORKSPACE/archive',
    // cwd for running the drp script
    DRP:       '$WORKSPACE/validate_drp',
    LSSTSW:    '$WORKSPACE/lsstsw',
    POSTQA:    '$WORKSPACE/post-qa',
    POSTQA_VERSION: '1.3.1',
    // validation data sets -- avoid variable name collision with EUPS
    HSC_DATA:  '$WORKSPACE/validation_data_hsc',
    JENKINS_DEBUG: 'true',
  )

  steps {
    // cleanup
    shell(
      '''
      #!/bin/bash -e

      [[ $JENKINS_DEBUG == true ]] && set -o xtrace

      # leave validate_drp results in workspace for debugging purproses but
      # always start with clean dirs

      rm -rf "$ARCHIVE" "$DRP"
      mkdir -p "$ARCHIVE" "$DRP"
      '''.replaceFirst("\n","").stripIndent()
    )

    // build/install validate_drp
    shell('''
      #!/bin/bash -e

      [[ $JENKINS_DEBUG == true ]] && set -o xtrace

      ./buildbot-scripts/jenkins_wrapper.sh
      '''.replaceFirst("\n","").stripIndent()
    )

    // run drp driver script
    shell(
      '''
      #!/bin/bash -e

      [[ $JENKINS_DEBUG == true ]] && set -o xtrace

      find_mem() {
        # Find system available memory in GiB
        local os
        os=$(uname)

        local sys_mem=""
        case $os in
          Linux)
            [[ $(grep MemAvailable /proc/meminfo) =~ \
               MemAvailable:[[:space:]]*([[:digit:]]+)[[:space:]]*kB ]]
            sys_mem=$((BASH_REMATCH[1] / 1024**2))
            ;;
          Darwin)
            # I don't trust this fancy greppin' an' matchin' in the shell.
            local free=$(vm_stat | grep 'Pages free:'     | \
              tr -c -d [[:digit:]])
            local inac=$(vm_stat | grep 'Pages inactive:' | \
              tr -c -d [[:digit:]])
            sys_mem=$(( (free + inac) / ( 1024 * 256 ) ))
            ;;
          *)
            >&2 echo "Unknown uname: $os"
            exit 1
            ;;
        esac

        echo "$sys_mem"
      }

      # find the maximum number of processes that may be run on the system
      # given the the memory per core ratio in GiB -- may be expressed in
      # floating point.
      target_cores() {
        local mem_per_core=${1:-1}

        local sys_mem=$(find_mem)
        local sys_cores
        sys_cores=$(getconf _NPROCESSORS_ONLN)

        # bash doesn't support floating point arithmetic
        local target_cores
        #target_cores=$(echo "$sys_mem / $mem_per_core" | bc)
        target_cores=$(awk "BEGIN{ print int($sys_mem / $mem_per_core) }")
        [[ $target_cores > $sys_cores ]] && target_cores=$sys_cores

        echo "$target_cores"
      }

      lfsconfig() {
        # Remove local configuration of lfs.batch.  This was once required to
        # be false with the sqre lfs server.  However, `false` will not break a
        # lfs pull.
        # git-config will exit 5 when trying to unset an undefined key
        git config --local --unset lfs.batch || true
        # lfs.required must be false in order for jenkins to manage the clone
        git config --local filter.lfs.required false
        git config --local filter.lfs.smudge 'git-lfs smudge %f'
        git config --local filter.lfs.clean 'git-lfs clean %f'
        git config --local credential.helper '!f() { cat > /dev/null; echo username=; echo password=; }; f'
      }

      cd "$DRP"

      # do not xtrace (if set) into setup.sh to avoid bloating the jenkins
      # console log
      SHOPTS=$(set +o)
      set +o xtrace
      . "${LSSTSW}/bin/setup.sh"
      eval "$SHOPTS"

      eval "$(grep -E '^BUILD=' "${LSSTSW}/build/manifest.txt")"

      DEPS=(validate_drp)

      for p in "${DEPS[@]}"; do
          setup "$p" -t "$BUILD"
      done

      #mkdir -p ~/.config/matplotlib
      #echo "backend: agg" > ~/.config/matplotlib/matplotlibrc

      case "$dataset" in
        cfht)
          RUN="$VALIDATE_DRP_DIR/examples/runCfhtTest.sh"
          RESULTS=(
            Cfht_output_r.json
          )
          LOGS=(
            'Cfht/processCcd.log'
          )
          ;;
        decam)
          RUN="$VALIDATE_DRP_DIR/examples/runDecamTest.sh"
          RESULTS=(
            Decam_output_z.json
          )
          LOGS=(
            'Decam/processCcd.log'
          )
          ;;
        hsc)
          RUN="$VALIDATE_DRP_DIR/examples/runHscTest.sh"
          RESULTS=(
            data_hsc_rerun_20170105_HSC-I.json
            data_hsc_rerun_20170105_HSC-R.json
            data_hsc_rerun_20170105_HSC-Y.json
          )
          LOGS=(
            'job_singleFrame.log'
          )

          ( set -e
            cd $HSC_DATA
            lfsconfig
            git lfs pull
          )
          setup -k -r $HSC_DATA
          ;;
        *)
          >&2 echo "Unknown DATASET: $dataset"
          exit 1
          ;;
      esac

      #rm -f ~/.config/matplotlib/matplotlibrc

      # pipe_drivers mpi implementation uses one core for orchestration, so we
      # need to set NUMPROC to the number of cores to utilize + 1
      MEM_PER_CORE=2.0
      export NUMPROC=$(($(target_cores $MEM_PER_CORE) + 1))

      # XXX testing cfht/decam dataset timeouts
      if [[ "$dataset" != "hsc" ]]; then
        export NUMPROC=1
      fi

      set +e
      "$RUN" --noplot
      run_status=$?
      set -e

      echo "${RUN##*/} - exit status: ${run_status}"

      # archive drp processing results
      # process artifacts *before* bailing out if the drp run failed
      archive_dir="${ARCHIVE}/${dataset}"
      mkdir -p "$archive_dir"
      artifacts=( "${RESULTS[@]}" "${LOGS[@]}" )

      for r in "${artifacts[@]}"; do
        dest="${archive_dir}/${r##*/}"
        # file may not exist due to an error
        if [[ ! -e "${DRP}/${r}" ]]; then
          continue
        fi
        if ! cp "${DRP}/${r}" "$dest"; then
          continue
        fi
        # compressing an example hsc output file
        # (cmd)       (ratio)  (time)
        # xz -T0      0.183    0:20
        # xz -T0 -9   0.180    1:23
        # xz -T0 -9e  0.179    1:28
        xz -T0 -9ev "$dest"
      done

      # bail out if the drp output file is missing
      if [[ ! -e  "${DRP}/${RESULTS[0]}" ]]; then
        echo "drp result file does not exist: ${DRP}/${RESULTS[0]}"
        exit 1
      fi

      # XXX we are currently only submitting one filter per dataset
      ln -sf "${DRP}/${RESULTS[0]}" "${DRP}/output.json"

      exit $run_status
      '''.replaceFirst("\n","").stripIndent()
    )

    // push results to squash
    shell(
      '''
      #!/bin/bash -e

      [[ $JENKINS_DEBUG == true ]] && set -o xtrace

      # bail out if the drp output file is missing
      if [[ ! -e  "${DRP}/output.json" ]]; then
        echo "drp result file does not exist: ${DRP}/output.json"
        exit 1
      fi

      archive_dir="${ARCHIVE}/${dataset}"
      mkdir -p "$archive_dir"

      mkdir -p "$POSTQA"
      cd "$POSTQA"

      virtualenv venv
      . venv/bin/activate
      pip install functools32
      pip install post-qa==$POSTQA_VERSION

      # archive post-qa output
      # XXX --api-url, --api-user, and --api-password are required even when --test is set
      postqa_output="${archive_dir}/post-qa.json"
      post-qa --lsstsw "$LSSTSW" --qa-json "${DRP}/output.json" --api-url "$SQUASH_URL"  --api-user "$SQUASH_USER" --api-password "$SQUASH_PASS" --test > "$postqa_output"
      xz -T0 -9ev "$postqa_output"

      # submit post-qa (temporarily sending to the current and to the new production enviroments)
      post-qa --lsstsw "$LSSTSW" --qa-json "${DRP}/output.json" --api-url "$SQUASH_URL"  --api-user "$SQUASH_USER" --api-password "$SQUASH_PASS"
      post-qa --lsstsw "$LSSTSW" --qa-json "${DRP}/output.json" --api-url "$SQUASH_NEW_URL"  --api-user "$SQUASH_NEW_USER" --api-password "$SQUASH_NEW_PASS"
      '''.replaceFirst("\n","").stripIndent()
    )
  }

  publishers {
    // we have to use postBuildScript here instead of the friendlier
    // postBuildScrips (plural) in order to use executeOn(), otherwise the
    // cleanup script is also run on the jenkins master
    postBuildScript {
      scriptOnlyIfSuccess(false)
      scriptOnlyIfFailure(true)
      markBuildUnstable(false)
      executeOn('AXES')
      buildStep {
        shell {
          command(
            '''
            Z=$(lsof -d 200 -t)
            if [[ ! -z $Z ]]; then
              kill -9 $Z
            fi

            rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
            '''.replaceFirst("\n","").stripIndent()
          )
        }
      }
    }
    archiveArtifacts {
      fingerprint()
      pattern('archive/**/*')
    }
  }
}

Common.addNotification(j)
