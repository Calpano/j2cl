goog.module('gen.java.lang.BooleanModule');


let Class = goog.require('gen.java.lang.CoreModule').Class;
let Object = goog.require('gen.java.lang.CoreModule').Object;
let $Util = goog.require('nativebootstrap.UtilModule').Util;


/**
 * Super-sourcing java/lang/Boolean.java.
 */
class Boolean extends Object {
  /**
   * Defines instance fields.
   * @private
   */
  constructor() {
    super();
  }

  /**
   * @param {boolean} obj
   * @return {boolean}
   * @public
   * @nocollapse
   */
  static $create__boolean(obj) {
    return obj;
  }

  /**
   * @param {boolean} x
   * @param {boolean} y
   * @return {number}
   * @public
   * @nocollapse
   */
  static m_compareTo__boolean__boolean(x, y) {
    return (x === y) ? 0 : (x ? 1 : -1);
  }

  /**
   * Returns whether the provided instance is an instance of this class.
   * @return {boolean}
   * @public
   * @nocollapse
   */
  static $isInstance(instance) {
    return typeof instance == 'boolean';
  }

  /**
   * Returns whether the provided class is or extends this class.
   * @param {Function} classConstructor
   * @return {boolean}
   * @public
   * @nocollapse
   */
  static $isAssignableFrom(classConstructor) {
    return $Util.$canCastClass(classConstructor, Boolean);
  }

  /**
   * @return {Class}
   * @public
   * @nocollapse
   */
  static $getClass() {
    if (!Boolean.$classBoolean) {
      Boolean.$classBoolean = Class.$createForClass(
          $Util.$generateId('Boolean'),
          $Util.$generateId('java.lang.Boolean'),
          Object.$getClass(),
          $Util.$generateId('java.lang.Boolean'));
    }
    return Boolean.$classBoolean;
  }
};


/**
 * Exported class.
 */
exports.Boolean = Boolean;
