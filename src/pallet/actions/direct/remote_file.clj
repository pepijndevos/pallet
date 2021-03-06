(ns pallet.actions.direct.remote-file
  "Action to specify remote file content."
  (:require
   [pallet.action :as action]
   [pallet.action-plan :as action-plan]
   [pallet.actions.direct.file :as file]
   [pallet.blobstore :as blobstore]
   [pallet.environment :as environment]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.template :as templates]
   [pallet.utils :as utils]
   [clojure.java.io :as io])
  (:use
   [pallet.action :only [implement-action]]
   [pallet.actions
    :only [exec-script transfer-file transfer-file-to-local delete-local-path]]
   [pallet.actions-impl
    :only [copy-filename md5-filename new-filename remote-file-action]]
   [pallet.utils :only [apply-map]]))

(implement-action delete-local-path :direct
  {:action-type :fn/clojure :location :origin}
  [session ^java.io.File local-path]
  [(fn [session]
     (.delete local-path)
     [local-path session])
   session])

(implement-action transfer-file-to-local :direct
  {:action-type :transfer/to-local :location :origin}
  [session remote-path local-path]
  [[(.getPath (io/file remote-path))
    (.getPath (io/file local-path))]
   session])

(implement-action transfer-file :direct
  {:action-type :transfer/from-local :location :origin}
  [session local-path remote-path remote-md5-path]
  [[(.getPath (io/file local-path))
    (.getPath (io/file remote-path))
    (.getPath (io/file remote-md5-path))]
   session])

(implement-action remote-file-action :direct
  {:action-type :script :location :target}
  [session path {:keys [action url local-file remote-file link
                        content literal
                        template values
                        md5 md5-url
                        owner group mode force
                        blob blobstore
                        install-new-files
                        overwrite-changes no-versioning max-versions
                        flag-on-changed
                        force
                        insecure]
                 :or {action :create max-versions 5
                      install-new-files true}
                 :as options}]
  [[{:language :bash}
    (let [new-path (new-filename path)
          md5-path (md5-filename path)
          copy-path (copy-filename path)
          versioning (if no-versioning nil :numbered)
          proxy (environment/get-for session [:proxy] nil)]
      (case action
        :create
        (action-plan/checked-commands
         (str "remote-file " path)

         ;; create the directory if required - note this is not the final
         ;; directory
         (stevedore/chained-script
          ("set" "-x")
          (lib/mkdir @(lib/dirname ~new-path) :path true))

         ;; Create the new content
         (cond
           (and url md5) (stevedore/chained-script
                          (if (chain-or (not (file-exists? ~path))
                                  (!= ~md5 @((pipe
                                              (~lib/md5sum ~path)
                                              (~lib/cut
                                               "" :fields 1 :delimiter " ")))))
                            ~(stevedore/chained-script
                              (~lib/download-file
                               ~url ~new-path
                               :proxy ~proxy :insecure ~insecure))))
           ;; Download md5 to temporary directory.
           (and url md5-url) (stevedore/chained-script
                              (var tmpdir (quoted (lib/make-temp-dir "rf")))
                              (var basefile
                                   (quoted
                                    (str @tmpdir "/" @(lib/basename ~path))))
                              (var newmd5path (quoted (str @basefile ".md5")))
                              (lib/download-file
                               ~md5-url @newmd5path :proxy ~proxy
                               :insecure ~insecure)
                              (lib/normalise-md5 @newmd5path)
                              (if (chain-or (not (file-exists? ~md5-path))
                                      (not (lib/diff @newmd5path ~md5-path)))
                                (do
                                  (lib/download-file
                                   ~url ~new-path :proxy ~proxy
                                   :insecure ~insecure)
                                  (lib/ln ~new-path @basefile)
                                  (if-not (~lib/md5sum-verify @newmd5path)
                                    (do
                                      (println ~(str "Download of " url
                                                     " failed to match md5"))
                                      (lib/rm @tmpdir
                                              :force ~true :recursive ~true)
                                      (lib/exit 1)))))
                              (lib/rm @tmpdir :force ~true :recursive ~true))
           url (stevedore/chained-script
                (~lib/download-file
                 ~url ~new-path :proxy ~proxy :insecure ~insecure))
           content (stevedore/script
                    (~lib/heredoc
                     ~new-path ~content ~(select-keys options [:literal])))
           local-file nil ; already copied in remote-file wrapper
           remote-file (stevedore/script
                        (~lib/cp ~remote-file ~new-path :force ~true))
           template (stevedore/script
                     (~lib/heredoc
                      ~new-path
                      ~(templates/interpolate-template
                        template (or values {}) session)
                      ~(select-keys options [:literal])))
           link (stevedore/script
                 (~lib/ln ~link ~path :force ~true :symbolic ~true))
           blob (action-plan/checked-script
                 "Download blob"
                 (~lib/download-request
                  ~new-path
                  ~(blobstore/sign-blob-request
                    (or blobstore (environment/get-for session [:blobstore] nil)
                        (throw (IllegalArgumentException.
                                "No :blobstore given for blob content.") ))
                    (:container blob) (:path blob)
                    {:method :get})))
           :else (throw
                  (IllegalArgumentException.
                   (str "remote-file " path " specified without content."))))

         ;; process the new file accordingly
         (when (and install-new-files (not link))
           (stevedore/chain-commands
            (if (or overwrite-changes no-versioning)
              (stevedore/script
               (if (file-exists? ~new-path)
                 (do
                   ~(stevedore/chain-commands
                     (stevedore/script
                      (lib/cp           ; copy to copy-path with versioning
                       ~new-path ~copy-path :backup ~versioning :force ~true)
                      (lib/mv ~new-path ~path :force ~true))
                     (if flag-on-changed
                       (stevedore/script (~lib/set-flag ~flag-on-changed)))))))
              (stevedore/chained-script
               ;; check if the file and the current copy are the same
               (var filediff "")
               (if (&& (file-exists? ~path) (file-exists? ~copy-path))
                 (do
                   (lib/diff ~path ~copy-path :unified true)
                   (set! filediff "$?")))
               ;; check if the current copy and the md5 match
               (var md5diff "")
               (if (&& (file-exists? ~copy-path) (file-exists? ~md5-path))
                 (do
                   (~lib/md5sum-verify ~md5-path)
                   (set! md5diff "$?")))
               ;; get the diff between current and new
               (var contentdiff "")
               (if (&& (file-exists? ~path) (file-exists? ~new-path))
                 (do
                   (~lib/diff ~path ~new-path :unified true)
                   (set! contentdiff "$?")))

               ;; report any errors
               (var errexit 0)
               (if (== @filediff 1)
                 (do
                   (println
                    "Existing file did not match the pallet master copy: FAIL")
                   (set! errexit 1)))

               (if (== @md5diff 1)
                 (do
                   (println "Existing content did not match md5: FAIL")
                   (set! errexit 1)))

               ;; exit if error
               (== @errexit 0)

               ;; install the file if the content is different
               (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
                 (do
                   ~(stevedore/chain-commands
                     (stevedore/script
                      (lib/cp
                       ~new-path ~copy-path :force ~true :backup ~versioning)
                      (lib/mv ~new-path ~path :force ~true))
                     (if flag-on-changed
                       (stevedore/script (lib/set-flag ~flag-on-changed))))))))
            (file/adjust-file path options)
            (when-not no-versioning
              (stevedore/chain-commands
               (file/write-md5-for-file path md5-path)
               (stevedore/script
                (println "MD5 sum is" @(~lib/cat ~md5-path)))))))
         ;; cleanup
         (if (and (not no-versioning) (pos? max-versions))
           (stevedore/script
            (pipe
             ((~lib/ls (str ~path ".~[0-9]*~") :sort-by-time ~true)
              "2>" "/dev/null")
             (~lib/tail "" :max-lines ~(str "+" (inc max-versions)))
             (~lib/xargs (~lib/rm "" :force ~true))))))
        :delete (action-plan/checked-script
                 (str "delete remote-file " path)
                 (~lib/rm ~path :force ~force))))]
   session])
