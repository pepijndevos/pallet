(ns pallet.compute.localhost
  "Localhost provider service implementation.

This enables your localhost to masquerade as a node for any group.

`run-nodes` will set the group-name for the localhost node, but is otherwise a
no-op.  The localhost node is marked as bootstrapped to prevent bootstrap
actions from occurring on group-name changes.

Node removal functions are no-ops."
  (:require
   [pallet.compute :as compute]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.node-list :as node-list]
   [pallet.configure :as configure]
   [pallet.core.api :refer [set-state-for-node]]
   [pallet.node :as node]
   [pallet.environment :as environment]
   [clojure.tools.logging :as logging]))

(deftype NodeTagEphemeral
    [tags]
  pallet.compute.NodeTagReader
  (node-tag [_ node tag-name]
    (@tags tag-name))
  (node-tag [_ node tag-name default-value]
    (@tags tag-name default-value))
  (node-tags [_ node]
    @tags)
  pallet.compute.NodeTagWriter
  (tag-node! [_ node tag-name value]
    (swap! tags assoc tag-name value))
  (node-taggable? [_ node] true))

(deftype LocalhostService [node environment tag-provider]
  pallet.compute/ComputeService
  (nodes [compute] [@node])
  (run-nodes [compute group-spec node-count user init-script options]
    (reset! node (node-list/make-localhost-node
                  :group-name (:group-name group-spec)
                  :service compute))
    ;; make sure we don't bootstrap
    (set-state-for-node :bootstrapped {:node @node})
    [@node])
  (reboot [compute nodes])
  (boot-if-down [compute nodes])
  (shutdown-node [compute node user])
  (shutdown [compute nodes user])
  (ensure-os-family [compute group-spec]
    (update-in group-spec [:image]
               #(merge {:os-family (node/os-family @node)
                        :os-version (node/os-version @node)})))
  (destroy-nodes-in-group [compute group-name])
  (destroy-node [compute node])
  (images [compute])
  (close [compute])
  pallet.environment.Environment
  (environment [_] environment)
  pallet.compute.NodeTagReader
  (node-tag [compute node tag-name]
    (compute/node-tag tag-provider node tag-name))
  (node-tag [compute node tag-name default-value]
    (compute/node-tag tag-provider node tag-name default-value))
  (node-tags [compute node]
    (compute/node-tags tag-provider node))
  pallet.compute.NodeTagWriter
  (tag-node! [compute node tag-name value]
    (compute/tag-node! tag-provider node tag-name value))
  (node-taggable? [compute node]
    (compute/node-taggable? tag-provider node))
  pallet.compute.ComputeServiceProperties
  (service-properties [_]
    {:provider :localhost
     :environment environment}))

;;;; Compute Service SPI

(defn supported-providers
  {:no-doc true
   :doc "Returns a sequence of providers that are supported"}
  [] ["localhost"])

;; service factory implementation for localhost provider

(defmethod implementation/service :localhost
  [provider {:keys [environment tag-provider]
             :or {tag-provider (->NodeTagEphemeral (atom {}))}
             :as options}]
  (let [service (LocalhostService. (atom nil) environment tag-provider)
        node (node-list/make-localhost-node :service service)]
    (reset! (.node service) node)
    (set-state-for-node :bootstrapped {:node node})
    service))
