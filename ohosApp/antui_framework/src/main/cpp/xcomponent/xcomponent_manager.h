#ifndef XCOMPONENT_MANAGER_H
#define XCOMPONENT_MANAGER_H
#include <map>
#include <string>
namespace AntUIFramework {
class XComponentManager {
 public:
  XComponentManager(const XComponentManager&) = delete;
  XComponentManager& operator=(const XComponentManager&) = delete;
  static XComponentManager& GetInstance() {
    static XComponentManager instance;
    return instance;
  }
 private:
  XComponentManager() {}
  ~XComponentManager() {}
};
}
#endif
