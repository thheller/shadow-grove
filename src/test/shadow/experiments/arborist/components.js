/**
 * @license
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// these were taken from
// https://github.com/webcomponents/custom-elements-everywhere/tree/master/libraries/__shared__/webcomponents/src

// copied here since to run the same compatibility tests
// https://github.com/webcomponents/custom-elements-everywhere

// copied here since the recommended project setup is built for JS projects
// and doesn't fit into how shadow-cljs works. didn't want to work with webpack
// just to get these.


class CEWithChildren extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({mode: 'open'});
    this.shadowRoot.innerHTML = `
      <h1>Test h1</h1>
      <div>
        <p>Test p</p>
      </div>
      <slot></slot>
    `;
  }
}

customElements.define('ce-with-children', CEWithChildren);

class CEWithEvent extends HTMLElement {
  constructor() {
    super();
    this.addEventListener('click', this.onClick);
  }
  onClick() {
    this.dispatchEvent(new CustomEvent('lowercaseevent'));
    this.dispatchEvent(new CustomEvent('kebab-event'));
    this.dispatchEvent(new CustomEvent('camelEvent'));
    this.dispatchEvent(new CustomEvent('CAPSevent'));
    this.dispatchEvent(new CustomEvent('PascalEvent'));
  }
}

customElements.define('ce-with-event', CEWithEvent);

class CEWithProperties extends HTMLElement {
  set bool(value) {
    this._bool = value;
  }
  get bool() {
    return this._bool;
  }
  set num(value) {
    this._num = value;
  }
  get num() {
    return this._num;
  }
  set str(value) {
    this._str = value;
  }
  get str() {
    return this._str;
  }
  set arr(value) {
    this._arr = value;
  }
  get arr() {
    return this._arr;
  }
  set obj(value) {
    this._obj = value;
  }
  get obj() {
    return this._obj;
  }
}

customElements.define('ce-with-properties', CEWithProperties);

class CEWithoutChildren extends HTMLElement {
  constructor() {
    super();
  }
}
customElements.define('ce-without-children', CEWithoutChildren);