// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package engine

import (
	"testing"
	"time"

	"github.com/apache/beam/sdks/v2/go/pkg/beam/core/graph/mtime"
	"github.com/apache/beam/sdks/v2/go/pkg/beam/core/graph/window"
	"github.com/apache/beam/sdks/v2/go/pkg/beam/core/typex"
)

func TestEarliestCompletion(t *testing.T) {
	tests := []struct {
		strat WinStrat
		input typex.Window
		want  mtime.Time
	}{
		{WinStrat{}, window.GlobalWindow{}, mtime.EndOfGlobalWindowTime},
		{WinStrat{}, window.IntervalWindow{Start: 0, End: 4}, 3},
		{WinStrat{}, window.IntervalWindow{Start: mtime.MinTimestamp, End: mtime.MaxTimestamp}, mtime.MaxTimestamp - 1},
		{WinStrat{AllowedLateness: 5 * time.Second}, window.GlobalWindow{}, mtime.EndOfGlobalWindowTime.Add(5 * time.Second)},
		{WinStrat{AllowedLateness: 5 * time.Millisecond}, window.IntervalWindow{Start: 0, End: 4}, 8},
		{WinStrat{AllowedLateness: 5 * time.Second}, window.IntervalWindow{Start: mtime.MinTimestamp, End: mtime.MaxTimestamp}, mtime.MaxTimestamp.Add(5 * time.Second)},
	}

	for _, test := range tests {
		if got, want := test.strat.EarliestCompletion(test.input), test.want; got != want {
			t.Errorf("%v.EarliestCompletion(%v)) = %v, want %v", test.strat, test.input, got, want)
		}
	}
}
