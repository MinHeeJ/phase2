import { Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { PlaceholderScreen } from "./PlaceholderScreen";
import { isScreenId, locateScreen } from "./registry";
import { useMenus } from "./useMenus";

export function SharedScreen({ screenId }: { screenId: string }) {
  const { menus, loading, error, retry } = useMenus();
  if (!isScreenId(screenId) || screenId === "UI-27")
    return <p>화면을 찾을 수 없습니다.</p>;
  const location = locateScreen(menus, screenId);
  return (
    <>
      <ErrorBanner error={error} onRetry={retry} pending={loading} />
      {loading && menus.length === 0 ? (
        <Skeleton label="메뉴 불러오는 중" />
      ) : location ? (
        <PlaceholderScreen screen={location.screen} group={location.group} />
      ) : screenId === "UI-22" ? (
        <PlaceholderScreen
          screen={{ screenId, name: screenId, mobile: true }}
        />
      ) : (
        !error && <p>화면을 찾을 수 없습니다.</p>
      )}
    </>
  );
}
