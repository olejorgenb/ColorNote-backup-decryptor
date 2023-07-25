(ns color-note-decryptor.decryptor
  (:gen-class)
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :refer [parse-opts]])
  (:import
    ColorNoteBackupDecrypt
    (java.io
      ByteArrayOutputStream
      FileInputStream)
    (java.security
      Security)
    (org.bouncycastle.jce.provider
      BouncyCastleProvider)))


(defn decrypt
  "Runs the original decryptor from BouncyCastleProvider"
  [{:keys [file offset password] :or {offset 28 password "0000"}}]
  (println (format "Decrypting using password %s and offset %s..." password offset))
  (Security/addProvider (BouncyCastleProvider.))
  (let [instance (doto (ColorNoteBackupDecrypt.) (.init password))]
    (with-open [raw-input (FileInputStream. file)
                out-stream (ByteArrayOutputStream.)]
      (.decrypt instance raw-input offset out-stream)
      (-> out-stream .toString))))


(defn fixup
  "Convert the output of decrypt to a vector of maps."
  [s]
  (let [pattern (re-pattern "\\{[^\\{\\}]*?\\}")]
    (->> s
         (re-seq pattern)
         (mapv #(json/parse-string % keyword)))))


(defn ->edn
  "Decrypt file, fixup and return as edn."
  [opts]
  (->> opts
       decrypt
       fixup))


(defn ->edn-file
  [{:keys [target] :as opts}]
  (->> opts
       ->edn
       (spit target)))


(defn ->json
  "Decrypt file, fixup and return as json string."
  [opts]
  (->> opts
       ->edn
       json/encode))


(defn ->json-file
  "Decrypt file and save as json under out-file."
  [{:keys [target] :as opts}]
  (->> opts
       ->json
       (spit target)))


(defn make-front-matter
  "Make YAML front-matter for markdown file."
  [note]
  (format "---\n%s---\n\n"
          (str/join
            (for [[k v] note
                  :when (not= "" v)]
              (str (name k) ": " v "\n")))))


(defn fixup-note
  "Fixup not map before saving to markdown. Removes extra keys, converts created dated to ISO."
  [note]
  (-> note
      (select-keys [:title :created_date :tags])
      (update :created_date #(str (java.time.Instant/ofEpochMilli %)))))


(defn note->md
  "Convert one not map from fixup to markdown map with keys :filename and :text"
  [note]
  (let [fixed (fixup-note note)]
    {:filename (str (:title note) ".md")
     :text (str (make-front-matter fixed) (:note note))}))


(defn ->markdown
  "Decrypt file, fixup and save all notes as separate markdown files under out-folder. Creates folder if it doesn't exist."
  [{:keys [target] :as opts}]
  (let [notes (->edn opts)
        md-notes (map note->md notes)
        cwd (fs/cwd)
        out (str cwd "/" target)]
    (when-not (fs/directory? out)
      (fs/create-dir out))
    (doseq [{:keys [filename text]} md-notes]
      (spit (str out "/" (str/replace filename "/" "-")) text))))


(def allowed-formats #{:json :markdown :edn})


(defmulti process (fn [{:keys [fmt target]}]
                    (let [res (cond
                                (empty? target) [:print fmt]
                                :else [:save fmt])]
                      (println res)
                      res) ))


(defmethod process [:print :json] [opts]
  (-> opts ->json println))


(defmethod process [:print :edn] [opts]
  (-> opts ->edn println))


(defmethod process [:save :json] [{:keys [target] :as opts}]
  (->json-file opts)
  (println target))


(defmethod process [:save :edn] [{:keys [target] :as opts}]
  (->edn-file opts)
  (println target))


(defmethod process [:save :markdown] [{:keys [target] :as opts}]
  (->markdown opts)
  (println target))


(def cli-opts
  [["-f" "--format FORMAT" "Output format, defaults to json"
    :id :fmt
    :default :json
    :validate [#(allowed-formats %) (str "Must be one of: " (str/join ", " allowed-formats))]
    :parse-fn keyword]
   ["-t" "--target TARGET" "Output file (for json and edn) or folder"
    :id :target]
   ["-p" "--password PASSWORD" "Optonal. A password to use for decryption, defaults to \"0000\""
    :id :password
    :default "0000"]
   ["-o" "--offset OFFSET" "Optional. Offset to use for decryption, defaults to 28"
    :id :offset
    :default 28
    :parse-fn parse-long]])


(defn prepare-options
  [& args]
  (let [parsed (parse-opts args cli-opts)
        {:keys [arguments options]} parsed
        {:keys [fmt target offset password]} options
        prepped {:file (first arguments)
                 :fmt fmt
                 :target target
                 :offset offset
                 :password password}]
    (cond
      (and (empty? arguments) (empty? target) (empty? fmt)) (assoc prepped :err :help)
      (empty? arguments) (assoc prepped :err "No Color Notes backup file specified")
      (and (= fmt :markdown) (nil? target)) (assoc prepped :err "You must specify a --target folder to save markdown files to.")
      :else prepped)))

(def help-message "Decrypts Color Notes backup into either a json, edn or a folder with markdown files (one file for each note).

Basic usage (will print the decrypted json):
  decryptor mynotes.backup

Available options:
  --format    -f   - specify output format [json, edn, markdown]
  --targret   -t   - specify the output file (or folder if format is markdown)
  --password  -p   - A password to use for decryption, defaults to \"0000\"
  --offset    -o   - Offset to use for decryption, defaults to 28 ")

(defn help []
  (println help-message))

(defn process-errors
  [{:keys [err]}]
  (when err
    (if (= :help err) (help)
        (println err))
    (System/exit 1)))


(defn -main
  [& args]
  (when (empty? args)
    (help)
    (System/exit 0))
  (let [options (apply prepare-options args)]
    (process-errors options)
    (process options)))




