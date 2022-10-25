// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Author: arnej

package main

import (
	"fmt"
	"os"
	"runtime"
	"strings"

	"github.com/vespa-engine/vespa/client/go/cmd/clusterstate"
	"github.com/vespa-engine/vespa/client/go/cmd/deploy"
	"github.com/vespa-engine/vespa/client/go/cmd/logfmt"
	"github.com/vespa-engine/vespa/client/go/script-utils/startcbinary"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

func basename(s string) string {
	parts := strings.Split(s, "/")
	return parts[len(parts)-1]
}

func main() {
	defer handleSimplePanic()
	action := basename(os.Args[0])
	if action == "script-utils" && len(os.Args) > 1 {
		action = os.Args[1]
		os.Args = os.Args[1:]
	}
	_ = vespa.FindHome()
	switch action {
	case "start-c-binary":
		os.Exit(startcbinary.Run(os.Args[1:]))
	case "export-env":
		vespa.ExportDefaultEnvToSh()
	case "security-env":
		vespa.ExportSecurityEnvToSh()
	case "ipv6-only":
		if vespa.HasOnlyIpV6() {
			os.Exit(0)
		} else {
			os.Exit(1)
		}
	case "vespa-deploy":
		cobra := deploy.NewDeployCmd()
		cobra.Execute()
	case "vespa-logfmt":
		cobra := logfmt.NewLogfmtCmd()
		cobra.Execute()
	case "vespa-get-cluster-state":
		cobra := clusterstate.NewGetClusterStateCmd()
		cobra.Execute()
	case "vespa-get-node-state":
		cobra := clusterstate.NewGetNodeStateCmd()
		cobra.Execute()
	case "vespa-set-node-state":
		cobra := clusterstate.NewSetNodeStateCmd()
		cobra.Execute()
	default:
		if startcbinary.IsCandidate(os.Args[0]) {
			os.Exit(startcbinary.Run(os.Args))
		}
		fmt.Fprintf(os.Stderr, "unknown action '%s'\n", action)
		fmt.Fprintln(os.Stderr, "actions: export-env, ipv6-only, security-env")
		fmt.Fprintln(os.Stderr, "(also: vespa-deploy, vespa-logfmt)")
	}
}

func handleSimplePanic() {
	if r := recover(); r != nil {
		if rte, ok := r.(runtime.Error); ok {
			panic(rte)
		} else if je, ok := r.(error); ok {
			fmt.Fprintln(os.Stderr, je)
			os.Exit(1)
		} else {
			panic(r)
		}
	}
}
