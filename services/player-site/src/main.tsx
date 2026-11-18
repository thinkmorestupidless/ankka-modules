import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

// The pages arrive with T043 to T045; until then the site serves this one.
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <main>Guyanabet</main>
  </StrictMode>,
);
